package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import kotlin.math.min

/**
 * Tuya-platform bulbs on the local protocol 3.3 (ADR 0007, docs/protocols/tuya.md). Patchbay never contacts Tuya: the
 * owner enters the device's LAN address, device ID and local key once (the key can only be fetched from Tuya's cloud,
 * by the owner, outside this app). Devices are added by address; there is no discovery.
 *
 * Only the data points in a [TuyaBulbProfile] are ever written or read. The local key is the pairing secret
 * (encrypted at rest, never logged). Protocol 3.4 and 3.5 devices are not supported yet and report that clearly.
 */
public class TuyaLocalAdapter(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter {

    public data class Config(
        val port: Int = TuyaProtocol.PORT,
        val ioTimeoutMs: Int = 3_000,
        val pollIntervalMs: Long = 6_000,
        val maxBackoffMs: Long = 60_000,
    )

    override val id: AdapterId = ID
    override val systemName: String = "Tuya bulbs"
    override val discoverable: Boolean = false
    override val requiresPairing: Boolean = true
    override val manualAdd: ManualAdd = ManualAdd(
        ID, "Tuya bulb (Lepro and others)", "Device ID (20–24 letters and numbers)", TuyaProfiles.ALL.map { it.label },
    )

    private class Known(val address: LanAddress, val profile: TuyaProfile, val localKey: ByteArray?)

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val locks = ConcurrentHashMap<LanAddress, Mutex>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val seq = AtomicInteger(1)

    /** Frames rejected as out of spec (diagnostics only). */
    @Volatile public var rejectedMessages: Long = 0
        private set

    override fun discover(): Flow<DiscoveredDevice> = emptyFlow() // by address only (Tuya's UDP announcements are not used)

    override fun manualDevice(address: String, key: String, choice: String?): PatchbayResult<DiscoveredDevice> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val deviceId = key.trim()
        if (!deviceId.matches(Regex("^[A-Za-z0-9]{20,24}$"))) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("A Tuya device ID is 20 to 24 letters and numbers"))
        }
        val profile = TuyaProfiles.byLabel(choice) ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("Choose a device type"))
        return PatchbayResult.Ok(
            DiscoveredDevice(
                adapter = ID, key = deviceId, address = a.literal, port = config.port, kind = profile.kind,
                vendor = "Tuya", model = profile.id, displayName = null,
                controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
            ),
        )
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = deviceIdFor(found.key)

    override fun capabilities(device: DeviceId): List<Capability> = (known[device]?.profile ?: TuyaBulbProfile.V2).capabilities

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val parts = pairing?.secret?.toString(Charsets.UTF_8)?.split('\n')
        val profile = parts?.getOrNull(0)?.let(TuyaProfiles::byId) ?: known[device]?.profile ?: TuyaBulbProfile.V2
        val key = parts?.getOrNull(1)?.takeIf { it.length == 16 }?.toByteArray(Charsets.UTF_8)
        known[device] = Known(a, profile, key)
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val profile = TuyaProfiles.byId(device.model ?: "") ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown device type"))
        val keyText = prompt.askForCode("Local key (16 characters from the Tuya developer console or tinytuya)", 16..16)
            ?: return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        if (keyText.length != 16 || keyText.any { it.code !in 33..126 }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("A Tuya local key is exactly 16 visible characters"))
        }
        val key = keyText.toByteArray(Charsets.US_ASCII)
        val id = deviceId(device)
        val k = Known(addr, profile, key)
        return when (val r = query(id, k, device.key)) {
            is PatchbayResult.Err -> r
            is PatchbayResult.Ok -> {
                if (!profile.recognises(r.value)) {
                    return PatchbayResult.Err(PatchbayError.Unsupported("The device didn't report a power switch where this device type expects it; try the other type"))
                }
                known[id] = k
                PatchbayResult.Ok(Pairing(id, "${profile.id}\n$keyText".toByteArray(Charsets.UTF_8), pinnedFingerprint = null))
            }
        }
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = config.pollIntervalMs
        while (currentCoroutineContext().isActive) {
            val k = known[device]
            val dps = if (k == null) null else (query(device, k, tuyaId(device)) as? PatchbayResult.Ok)?.value
            if (dps != null) {
                failures = 0
                backoff = config.pollIntervalMs
                lastContact[device] = clock()
                emit(DeviceSnapshot(device, Reachability.ONLINE, k!!.profile.states(dps), EpochMillis(clock())))
            } else {
                failures++
                emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                backoff = min(backoff * 2, config.maxBackoffMs)
            }
            delay(if (failures == 0) config.pollIntervalMs else backoff)
        }
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown device"))
        val key = k.localKey ?: return PatchbayResult.Err(PatchbayError.Unauthorized("enter the device's local key first"))
        val dps = when (val r = k.profile.toDps(command)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val plain = TuyaProtocol.controlJson(tuyaId(device), dps, clock() / 1000)
        val payload = TuyaProtocol.VERSION_HEADER + TuyaCipher.encrypt(key, plain)
        return when (val r = exchange(k.address, TuyaProtocol.CMD_CONTROL, payload)) {
            is PatchbayResult.Err -> r
            is PatchbayResult.Ok -> checkReturn(r.value)
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not added yet")
        if (k.localKey == null) return AdapterHealth(HealthState.UNPAIRED, null, "needs its local key")
        val ok = query(device, k, tuyaId(device)) is PatchbayResult.Ok
        return AdapterHealth(if (ok) HealthState.OK else HealthState.UNREACHABLE, lastContact[device]?.let(::EpochMillis), null)
    }

    private suspend fun query(device: DeviceId, k: Known, tuyaId: String): PatchbayResult<Map<String, JsonPrimitive>> {
        val key = k.localKey ?: return PatchbayResult.Err(PatchbayError.Unauthorized("enter the device's local key first"))
        val payload = TuyaCipher.encrypt(key, TuyaProtocol.dpQueryJson(tuyaId, clock() / 1000))
        val frame = when (val r = exchange(k.address, TuyaProtocol.CMD_DP_QUERY, payload)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        (checkReturn(frame) as? PatchbayResult.Err)?.let { return it }
        val plain = try {
            TuyaCipher.decrypt(key, TuyaProtocol.stripVersionHeader(frame.payload))
        } catch (e: BadPaddingException) {
            return PatchbayResult.Err(PatchbayError.Unauthorized("the device didn't accept the local key"))
        } catch (e: IllegalBlockSizeException) {
            rejectedMessages++
            return PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: reply wasn't a protocol 3.3 message"))
        }
        return TuyaProtocol.parseDps(plain).also { if (it is PatchbayResult.Err) rejectedMessages++ else lastContact[device] = clock() }
    }

    private fun checkReturn(f: TuyaProtocol.Frame): PatchbayResult<Unit> =
        if (f.retcode == 0) PatchbayResult.Ok(Unit) else PatchbayResult.Err(PatchbayError.Unauthorized("the device refused the request; check the device ID and local key"))

    /** One request and one reply on a fresh connection (Tuya bulbs accept very few at once), serialised per device. */
    private suspend fun exchange(addr: LanAddress, cmd: Int, payload: ByteArray): PatchbayResult<TuyaProtocol.Frame> =
        locks.getOrPut(addr) { Mutex() }.withLock {
            val socket = when (val c = netGuard.connectTcp(addr, config.port, config.ioTimeoutMs)) {
                is PatchbayResult.Ok -> c.value
                is PatchbayResult.Err -> return@withLock c
            }
            withContext(Dispatchers.IO) {
                try {
                    socket.use {
                        it.getOutputStream().apply { write(TuyaProtocol.encode(seq.getAndIncrement(), cmd, payload)); flush() }
                        val input = it.getInputStream()
                        val header = input.readExactly(TuyaProtocol.HEADER_BYTES)
                        val remaining = when (val n = TuyaProtocol.remainingAfterHeader(header)) {
                            is PatchbayResult.Ok -> n.value
                            is PatchbayResult.Err -> { rejectedMessages++; return@use n }
                        }
                        TuyaProtocol.decode(header + input.readExactly(remaining), fromDevice = true)
                            .also { r -> if (r is PatchbayResult.Err) rejectedMessages++ }
                    }
                } catch (e: SocketTimeoutException) {
                    PatchbayResult.Err(PatchbayError.Timeout("the device didn't answer"))
                } catch (e: IOException) {
                    PatchbayResult.Err(PatchbayError.Unreachable("connection failed: ${e.javaClass.simpleName}"))
                }
            }
        }

    private fun java.io.InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) throw IOException("closed")
            off += r
        }
        return out
    }

    private fun tuyaId(device: DeviceId): String = device.value.removePrefix("tuya-")

    public companion object {
        public val ID: AdapterId = AdapterId("tuya-local")
        public fun deviceIdFor(key: String): DeviceId = DeviceId("tuya-$key")
    }
}
