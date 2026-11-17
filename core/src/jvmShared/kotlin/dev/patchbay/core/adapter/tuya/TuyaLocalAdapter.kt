package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.RecentProblems
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Tuya-platform bulbs on the local protocol, versions 3.3, 3.4 and 3.5 (ADR 0007, docs/protocols/tuya.md). Patchbay never
 * contacts Tuya: the owner enters the device's LAN address, device ID and local key once (the key can only be fetched from
 * Tuya's cloud, by the owner, outside this app). Devices are added by address; there is no discovery. Which protocol
 * version a device speaks depends on its firmware, so pairing tries each in turn and remembers the one that worked.
 *
 * Only the data points in a [TuyaBulbProfile] are ever written or read. The local key is the pairing secret
 * (encrypted at rest, never logged).
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

    private class Known(val address: LanAddress, val profile: TuyaProfile, val localKey: ByteArray?, val version: TuyaVersion = TuyaVersion.V33) {
        /** Set once a protocol 3.3 device has answered the normal query with "data unvalid": it is then asked for its data points by number. */
        @Volatile var device22: Boolean = false

        /** The data point numbers that answered when they were looked for by number, so later readings ask for just those (see [knownDpsAt]). */
        @Volatile var knownDps: List<String>? = null

        /** When [knownDps] was found. A device type that does not know its own numbers looks through the ranges again from time to time, because some data points only appear in some states (a vacuum while it cleans). */
        @Volatile var knownDpsAt: Long = 0
    }

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val locks = ConcurrentHashMap<LanAddress, Mutex>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val seq = AtomicInteger(1)
    private val random = SecureRandom()

    /** Frames rejected as out of spec (diagnostics only). */
    @Volatile public var rejectedMessages: Long = 0
        private set

    private val problems = RecentProblems()
    private val dpShapes = ConcurrentHashMap<DeviceId, String>()

    private fun rejected(why: String) {
        rejectedMessages++
        problems.add(why)
    }

    /** Data point numbers with the *type* of each value (and a string's length), never the values: a vacuum's map or a bulb's scene is not for a report. */
    private fun shapeOf(dps: Map<String, JsonPrimitive>): String =
        dps.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.take(MAX_DPS_IN_REPORT).joinToString(" ") { (k, v) ->
            val type = when {
                v.isString -> "string(${v.content.length})"
                v.booleanOrNull != null -> "bool"
                v.longOrNull != null -> "int"
                v.doubleOrNull != null -> "float"
                else -> "other"
            }
            "${k.filter { it.isLetterOrDigit() }.take(6)}=$type"
        }

    override fun diagnostics(device: DeviceId): List<String> = buildList {
        val k = known[device]
        add(
            "device type: ${k?.profile?.id ?: "unknown"}; local key stored: ${k?.localKey != null}; protocol spoken: ${k?.version?.label ?: "unknown"}; " +
                "query style: ${if (k?.device22 == true) "by data point number (a \"device22\" device)" else "normal"}",
        )
        dpShapes[device]?.let { add("data points in the last reading (type only): $it") }
    }

    override fun adapterDiagnostics(): List<String> = listOf("frames rejected as out of spec: $rejectedMessages") + problems.list().map { "problem: $it" }

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
        // Secrets saved before 3.4 and 3.5 were supported have no third line: they are 3.3.
        val version = TuyaVersion.byLabel(parts?.getOrNull(2)) ?: known[device]?.version ?: TuyaVersion.V33
        val previous = known[device]
        known[device] = Known(a, profile, key, version).also {
            // A fourth line says the device had to be asked for its data points by number; what a moved device had already found out is kept.
            it.device22 = parts?.getOrNull(3) == "device22" || previous?.device22 == true
            it.knownDps = previous?.knownDps
            it.knownDpsAt = previous?.knownDpsAt ?: 0
        }
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
        // Which version a device speaks depends on its firmware and is not announced to us, so try each and keep the one that answers.
        val errors = mutableListOf<PatchbayError>()
        for (version in TuyaVersion.entries) {
            val k = Known(addr, profile, key, version)
            when (val r = query(id, k, device.key)) {
                is PatchbayResult.Err -> errors += r.error
                is PatchbayResult.Ok -> {
                    if (!profile.recognises(r.value)) {
                        return PatchbayResult.Err(PatchbayError.Unsupported("The device didn't report a power switch where this device type expects it; try the other type"))
                    }
                    known[id] = k
                    val secret = "${profile.id}\n$keyText\n${version.label}" + if (k.device22) "\ndevice22" else ""
                    return PatchbayResult.Ok(Pairing(id, secret.toByteArray(Charsets.UTF_8), pinnedFingerprint = null))
                }
            }
        }
        return PatchbayResult.Err(pairFailure(errors))
    }

    /**
     * What to tell the owner when no protocol version worked. A device that refused the key, or sent something that isn't Tuya, says so; one that
     * accepted the connection and then never gave a usable answer in any version almost always has a different local key (it changes whenever the
     * device is removed from the Tuya app and added again); anything else (nothing at that address) is reported as it was.
     */
    private fun pairFailure(errors: List<PatchbayError>): PatchbayError =
        errors.firstOrNull { it is PatchbayError.Unauthorized }
            ?: errors.firstOrNull { it is PatchbayError.ProtocolViolation }
            ?: if (errors.all { it is PatchbayError.Timeout || (it is PatchbayError.Unreachable && it.message.startsWith(CLOSED)) }) {
                PatchbayError.Unauthorized("The device answered the connection but not with the protocol (3.3, 3.4 or 3.5) and local key you gave. Check the device ID and the local key")
            } else errors.first()

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
        val json = if (k.version == TuyaVersion.V33) TuyaProtocol.controlJson(tuyaId(device), dps, clock() / 1000) else TuyaProtocol.controlJsonNew(dps, clock() / 1000)
        return when (val r = withLink(k, key) { it.control(json) }) {
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
        if (k.version == TuyaVersion.V33 && k.device22) return queryByNumber(device, k, tuyaId, key)
        val json = if (k.version == TuyaVersion.V33) TuyaProtocol.dpQueryJson(tuyaId, clock() / 1000) else TuyaProtocol.dpQueryJsonNew()
        val reply = when (val r = withLink(k, key) { it.query(json) }) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        // Some protocol 3.3 devices (often with 22-character ids) answer the normal query with "data unvalid" and want their data points asked for by number.
        if (k.version == TuyaVersion.V33 && reply.plain.decodeToString().contains("data unvalid", ignoreCase = true)) {
            k.device22 = true
            problems.add("the device answered \"data unvalid\" to the normal query; asking for its data points by number instead")
            return queryByNumber(device, k, tuyaId, key)
        }
        (checkReturn(reply) as? PatchbayResult.Err)?.let { return it }
        return noteReading(device, TuyaProtocol.parseDps(reply.plain))
    }

    private fun noteReading(device: DeviceId, r: PatchbayResult<Map<String, JsonPrimitive>>): PatchbayResult<Map<String, JsonPrimitive>> = r.also {
        when (it) {
            is PatchbayResult.Err -> rejected(it.error.message)
            is PatchbayResult.Ok -> { lastContact[device] = clock(); dpShapes[device] = shapeOf(it.value) }
        }
    }

    /**
     * The "device22" way of reading: the control-style command with the data point numbers wanted. The profile's own numbers are asked for in one request; a profile
     * that does not know its numbers (the read-only one) tries the ranges devices use, once, and remembers which numbers answered.
     */
    private suspend fun queryByNumber(device: DeviceId, k: Known, tuyaId: String, key: ByteArray): PatchbayResult<Map<String, JsonPrimitive>> {
        suspend fun ask(ids: List<String>): PatchbayResult<Map<String, JsonPrimitive>> {
            val reply = when (val r = withLink(k, key) { it.queryDps(TuyaProtocol.dpQueryByNumberJson(tuyaId, ids, clock() / 1000)) }) {
                is PatchbayResult.Ok -> r.value
                is PatchbayResult.Err -> return r
            }
            (checkReturn(reply) as? PatchbayResult.Err)?.let { return it }
            return TuyaProtocol.parseDps(reply.plain)
        }
        k.profile.requestDps?.let { return noteReading(device, ask(it)) }
        val remembered = k.knownDps
        if (remembered != null && clock() - k.knownDpsAt < RESCAN_MS) return noteReading(device, ask(remembered))
        // Unknown numbers: look through the usual ranges (a request can only carry so many), always including 1.
        val found = LinkedHashMap<String, JsonPrimitive>()
        var lastError: PatchbayResult.Err? = null
        for (range in DISCOVERY_RANGES) {
            when (val r = ask(listOf("1") + range.map { it.toString() })) {
                is PatchbayResult.Ok -> found.putAll(r.value)
                is PatchbayResult.Err -> lastError = r
            }
        }
        if (found.isEmpty()) return noteReading(device, lastError ?: PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: the device reported no data points")))
        k.knownDps = found.keys.toList()
        k.knownDpsAt = clock()
        return noteReading(device, PatchbayResult.Ok(found))
    }

    private fun checkReturn(r: TuyaReply): PatchbayResult<Unit> =
        if (r.retcode == 0) PatchbayResult.Ok(Unit) else {
            problems.add("the device answered with return code ${r.retcode} (wrong device ID or local key?)")
            PatchbayResult.Err(PatchbayError.Unauthorized("the device refused the request; check the device ID and local key"))
        }

    /**
     * One connection for one job (Tuya bulbs accept very few at once), serialised per device: connect, agree a session key if the protocol has one,
     * run [block], hang up. Failures are noted for the diagnostics report and returned as errors, never thrown.
     */
    private suspend fun <T> withLink(k: Known, key: ByteArray, block: (TuyaLink) -> PatchbayResult<T>): PatchbayResult<T> =
        locks.getOrPut(k.address) { Mutex() }.withLock {
            val socket = when (val c = netGuard.connectTcp(k.address, config.port, config.ioTimeoutMs)) {
                is PatchbayResult.Ok -> c.value
                is PatchbayResult.Err -> return@withLock c
            }
            withContext(Dispatchers.IO) {
                try {
                    socket.use {
                        val link = when (val l = TuyaLinks.open(k.version, key, it.getInputStream(), it.getOutputStream(), seq, random)) {
                            is PatchbayResult.Ok -> l.value
                            is PatchbayResult.Err -> return@use noted(l)
                        }
                        noted(block(link))
                    }
                } catch (e: SocketTimeoutException) {
                    problems.add("no answer within ${config.ioTimeoutMs} ms (protocol ${k.version.label})")
                    PatchbayResult.Err(PatchbayError.Timeout("the device didn't answer"))
                } catch (e: IOException) {
                    problems.add("connection failed: ${e.javaClass.simpleName} (protocol ${k.version.label})")
                    PatchbayResult.Err(
                        PatchbayError.Unreachable(if (e.message == "closed") "$CLOSED (protocol ${k.version.label})" else "connection failed: ${e.javaClass.simpleName}"),
                    )
                }
            }
        }

    private fun <T> noted(r: PatchbayResult<T>): PatchbayResult<T> {
        if (r is PatchbayResult.Err) {
            val e = r.error
            if (e is PatchbayError.ProtocolViolation) rejected(e.message) else if (e is PatchbayError.Unauthorized) problems.add("the device didn't accept the stored local key")
        }
        return r
    }

    private fun tuyaId(device: DeviceId): String = device.value.removePrefix("tuya-")

    public companion object {
        public val ID: AdapterId = AdapterId("tuya-local")
        public fun deviceIdFor(key: String): DeviceId = DeviceId("tuya-$key")
        private const val MAX_DPS_IN_REPORT = 30
        private const val CLOSED = "the device closed the connection"

        /** How long a read-only device's data point numbers are trusted before the ranges are searched again. */
        private const val RESCAN_MS = 20_000L

        /** Where data point numbers are found in practice; asked for in these groups because a request has a small size limit. */
        private val DISCOVERY_RANGES: List<IntRange> = listOf(2..10, 11..20, 21..30, 50..60, 61..70, 100..110, 111..120, 121..130, 131..140)
    }
}
