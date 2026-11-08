package dev.patchbay.core.adapter.homekit

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.HvacMode
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Thermostats that speak HomeKit (HAP over IP), ecobee first. Local only: pairing with the setup code, then an encrypted
 * session on the LAN. No Apple account, no cloud (ADR 0010).
 *
 * What HomeKit exposes is what you get: temperature, humidity, mode, setpoints and occupancy sensors. ecobee's Home/Away/Sleep
 * comfort modes and its air-quality readings are not part of the HomeKit model. The accessory can be paired to one controller
 * family at a time, so it must first be removed from Apple Home.
 */
public class HomeKitAdapter(
    private val netGuard: NetGuard,
    private val finder: CastAdapter.CastFinder,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter {

    public data class Config(
        val connectTimeoutMs: Int = 4_000,
        val ioTimeoutMs: Int = 6_000,
        val pollIntervalMs: Long = 5_000,
        val maxBackoffMs: Long = 60_000,
    )

    override val id: AdapterId = ID
    override val requiresPairing: Boolean = true
    override val systemName: String = "HomeKit thermostats (ecobee)"
    override val discoveryNote: String =
        "The thermostat must be on this network. If it is paired to Apple Home, remove it there first (it accepts one pairing family at a time). Its setup code is under Settings > HomeKit on the thermostat."

    private class Known(val address: LanAddress, @Volatile var port: Int, var keys: HapPairingKeys?, val name: String)

    private class Link(val conn: HapConnection, val layout: ThermostatLayout)

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val links = ConcurrentHashMap<DeviceId, Link>()
    private val locks = ConcurrentHashMap<DeviceId, Mutex>()
    private val wakes = ConcurrentHashMap<DeviceId, Channel<Unit>>()
    private val layouts = ConcurrentHashMap<DeviceId, ThermostatLayout>()
    private val trustBroken = ConcurrentHashMap.newKeySet<DeviceId>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val lastMode = ConcurrentHashMap<DeviceId, HvacMode>()

    override fun trustChanged(device: DeviceId): Boolean = device in trustBroken

    override fun discover(): Flow<DiscoveredDevice> = flow {
        for (f in finder.find()) {
            val hap = f.hap ?: continue
            if (hap.category != null && hap.category != CATEGORY_THERMOSTAT) continue // v1 handles thermostats only
            val pairedElsewhere = hap.unpaired == false
            val model = f.model?.take(32)
            emit(
                DiscoveredDevice(
                    adapter = ID, key = keyFor(hap.deviceId, f.address), address = f.address, port = hap.port,
                    kind = DeviceKind.THERMOSTAT, vendor = if (model?.contains("ecobee", ignoreCase = true) == true) "ecobee" else "HomeKit",
                    model = (model ?: "Thermostat") + if (pairedElsewhere) " (paired to another controller)" else "",
                    displayName = f.friendlyName?.take(48)?.takeIf { it.isNotBlank() },
                    controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
                ),
            )
        }
    }

    private fun keyFor(hapId: String?, address: String): String {
        val clean = hapId?.lowercase()?.filter { it.isLetterOrDigit() }?.take(24)?.takeIf { it.length >= 6 }
        return clean ?: ("ip-" + address.map { if (it.isLetterOrDigit()) it else '-' }.joinToString(""))
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("homekit-${found.key.lowercase()}")

    override fun capabilities(device: DeviceId): List<Capability> = layouts[device]?.capabilities() ?: DEFAULT_CAPABILITIES

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val keys = pairing?.secret?.let { HapPairingKeys.decode(it) }
        val prev = known[device]
        if (prev != null && prev.address != a) drop(device) // the thermostat moved (new DHCP lease): reconnect there
        known[device] = Known(a, prev?.port ?: keys?.port?.takeIf { it > 0 } ?: DEFAULT_PORT, keys ?: prev?.keys, prev?.name ?: "thermostat")
        return PatchbayResult.Ok(Unit)
    }

    /** The setup code is typed with or without dashes; HAP wants "XXX-XX-XXX". Null when it isn't eight digits. */
    internal fun normaliseCode(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 8 || digits.length != raw.count { it.isDigit() }) return null
        if (raw.any { !it.isDigit() && it != '-' && it != ' ' }) return null
        if (digits in FORBIDDEN_CODES) return null
        return "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}"
    }

    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val port = device.port ?: DEFAULT_PORT
        val name = device.displayName ?: device.model ?: "thermostat"
        val raw = prompt.askForCode("Setup code from the thermostat (XXX-XX-XXX)", 8..11)
            ?: return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        val code = normaliseCode(raw) ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("The setup code is eight digits, like 123-45-678."))
        val id = deviceId(device)

        val keys = when (val r = connect(addr, port)) {
            is PatchbayResult.Err -> return r
            is PatchbayResult.Ok -> r.value.use { conn ->
                try {
                    withContext(Dispatchers.IO) { HapPairing.setup(conn, code) }
                } catch (e: HapException) {
                    return PatchbayResult.Err(mapPairingError(e))
                } catch (e: IOException) {
                    return PatchbayResult.Err(PatchbayError.Unreachable("lost the connection while pairing"))
                }
            }
        }
        val storedKeys = keys.withPort(port)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(keys.accessoryPublicKey).joinToString("") { "%02x".format(it) }
        if (!prompt.confirmFingerprint(name, fingerprint)) {
            removePairing(addr, port, keys) // best effort: don't leave a pairing the user rejected
            return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        }
        known[id] = Known(addr, port, storedKeys, name)
        return PatchbayResult.Ok(Pairing(id, storedKeys.encode(), fingerprint))
    }

    private suspend fun removePairing(addr: LanAddress, port: Int, keys: HapPairingKeys) {
        val conn = (connect(addr, port) as? PatchbayResult.Ok)?.value ?: return
        conn.use {
            try { withContext(Dispatchers.IO) { HapPairing.verify(it, keys); HapPairing.remove(it, keys) } } catch (_: IOException) {}
        }
    }

    /** Asks the thermostat to drop our pairing, so removing it here doesn't leave it unable to be paired again. */
    override suspend fun unpair(device: DeviceId) {
        val k = known[device] ?: return
        val keys = k.keys ?: return
        drop(device)
        removePairing(k.address, k.port, keys)
        known.remove(device)
    }

    private fun mapPairingError(e: HapException): PatchbayError = when (e.kind) {
        HapException.Kind.WRONG_CODE -> PatchbayError.Unauthorized("The thermostat did not accept that setup code. Check it on the thermostat and try again.")
        HapException.Kind.ALREADY_PAIRED -> PatchbayError.Unsupported("The thermostat is already paired. Remove it from Apple Home (or other HomeKit apps) first, then try again.")
        HapException.Kind.TOO_MANY_TRIES -> PatchbayError.Unauthorized("The thermostat locked pairing after wrong codes. Restart pairing on the thermostat and retry.")
        HapException.Kind.BUSY -> PatchbayError.Timeout("The thermostat is busy. Wait a minute and try again.")
        HapException.Kind.UNTRUSTED_ACCESSORY -> PatchbayError.Unauthorized("The thermostat could not prove it knows the setup code. Nothing was saved.")
        HapException.Kind.IO -> PatchbayError.Unreachable("lost the connection while pairing")
        else -> PatchbayError.ProtocolViolation("unexpected pairing reply: ${e.message}")
    }

    private suspend fun connect(addr: LanAddress, port: Int): PatchbayResult<HapConnection> =
        when (val r = netGuard.connectTcp(addr, port, config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> {
                r.value.soTimeout = config.ioTimeoutMs
                PatchbayResult.Ok(HapConnection(r.value, addr.literal))
            }
            is PatchbayResult.Err -> r
        }

    private val lastPortRefresh = ConcurrentHashMap<DeviceId, Long>()

    /** HomeKit accessories may choose a new port after a reboot: when the stored one refuses us, look at what the thermostat announces now (at most every 30 s). */
    private suspend fun connectKnown(device: DeviceId, k: Known): PatchbayResult<HapConnection> {
        val first = connect(k.address, k.port)
        if (first is PatchbayResult.Ok) return first
        val now = clock()
        if (now - (lastPortRefresh[device] ?: 0L) < 30_000) return first
        lastPortRefresh[device] = now
        val advertised = finder.find().firstOrNull { it.address == k.address.literal && it.hap != null }?.hap?.port ?: return first
        if (advertised == k.port) return first
        k.port = advertised
        return connect(k.address, k.port)
    }

    /** Runs [block] on the verified session for [device], connecting (pair-verify + accessory list) first when needed. */
    private suspend fun <T> withLink(device: DeviceId, block: (Link) -> T): PatchbayResult<T> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown thermostat"))
        val keys = k.keys ?: return PatchbayResult.Err(PatchbayError.Unauthorized("not paired"))
        if (device in trustBroken) return PatchbayResult.Err(PatchbayError.TrustChanged(k.name, "${k.name} presented a different key than the one you paired"))
        return locks.getOrPut(device) { Mutex() }.withLock {
            try {
                val link = links[device] ?: when (val c = connectKnown(device, k)) {
                    is PatchbayResult.Err -> return@withLock c
                    is PatchbayResult.Ok -> withContext(Dispatchers.IO) {
                        try {
                            HapPairing.verify(c.value, keys)
                            val resp = c.value.request("GET", "/accessories", accept = HapConnection.JSON)
                            if (resp.status != 200) throw HapException(HapException.Kind.REJECTED, "accessory list refused (HTTP ${resp.status})")
                            val db = HapDb.parseAccessories(resp.body) ?: throw HapException(HapException.Kind.PROTOCOL, "unreadable accessory list")
                            val layout = ThermostatLayout.find(db) ?: throw HapException(HapException.Kind.PROTOCOL, "no thermostat found on this accessory")
                            Link(c.value, layout)
                        } catch (e: Exception) {
                            c.value.close(); throw e
                        }
                    }.also { links[device] = it; layouts[device] = it.layout }
                }
                PatchbayResult.Ok(withContext(Dispatchers.IO) { block(link) })
            } catch (e: HapException) {
                drop(device)
                if (e.kind == HapException.Kind.UNTRUSTED_ACCESSORY) {
                    trustBroken += device
                    PatchbayResult.Err(PatchbayError.TrustChanged(k.name, e.message ?: "untrusted accessory"))
                } else {
                    PatchbayResult.Err(PatchbayError.Unreachable("HomeKit session failed: ${e.message}"))
                }
            } catch (e: java.net.SocketTimeoutException) {
                drop(device); PatchbayResult.Err(PatchbayError.Timeout("thermostat didn't answer"))
            } catch (e: IOException) {
                drop(device); PatchbayResult.Err(PatchbayError.Unreachable("connection failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    private fun drop(device: DeviceId) { links.remove(device)?.conn?.close() }

    private fun read(link: Link): List<CapabilityState>? {
        val resp = link.conn.request("GET", HapDb.readUrl(link.layout.readIds), accept = HapConnection.JSON)
        if (resp.status != 200 && resp.status != 207) throw HapException(HapException.Kind.REJECTED, "read refused (HTTP ${resp.status})")
        val values = HapDb.parseValues(resp.body) ?: throw HapException(HapException.Kind.PROTOCOL, "unreadable values")
        return link.layout.states(values)
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = config.pollIntervalMs
        val wake = wakes.getOrPut(device) { Channel(Channel.CONFLATED) }
        while (currentCoroutineContext().isActive) {
            if (device in trustBroken) {
                emit(DeviceSnapshot(device, Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                withTimeoutOrNull(config.maxBackoffMs) { wake.receive() }
                continue
            }
            when (val r = withLink(device) { read(it) }) {
                is PatchbayResult.Ok -> {
                    val states = r.value
                    if (states == null) {
                        failures++
                        emit(DeviceSnapshot(device, Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                    } else {
                        failures = 0; backoff = config.pollIntervalMs
                        lastContact[device] = clock()
                        states.filterIsInstance<CapabilityState.ThermostatState>().firstOrNull()?.let { lastMode[device] = it.mode }
                        emit(DeviceSnapshot(device, Reachability.ONLINE, states, EpochMillis(clock())))
                    }
                }
                is PatchbayResult.Err -> {
                    failures++
                    emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                    backoff = min(backoff * 2, config.maxBackoffMs)
                }
            }
            withTimeoutOrNull(if (failures == 0) config.pollIntervalMs else backoff) { wake.receive() }
        }
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val result = withLink(device) { link -> write(link, command) }
        val outcome = when (result) {
            is PatchbayResult.Err -> return result
            is PatchbayResult.Ok -> result.value
        }
        if (outcome is PatchbayResult.Ok) wakes[device]?.trySend(Unit)
        return outcome
    }

    private fun write(link: Link, command: Command): PatchbayResult<Unit> {
        val l = link.layout
        val writes: List<Pair<HapChar, JsonElement>> = when (command) {
            is Command.SetHvacMode -> {
                if (command.mode !in l.modes) return PatchbayResult.Err(PatchbayError.Unsupported("this thermostat doesn't offer ${command.mode.name.lowercase()}"))
                listOf(l.targetState to JsonPrimitive(ThermostatLayout.codeOf(command.mode)))
            }
            is Command.SetSetpoints -> {
                val mode = currentMode(link) ?: return PatchbayResult.Err(PatchbayError.Unreachable("couldn't read the thermostat's mode"))
                fun num(c: Celsius) = JsonPrimitive((c.value * 10).roundToLong() / 10.0)
                when (mode) {
                    HvacMode.OFF -> return PatchbayResult.Err(PatchbayError.InvalidArgument("The thermostat is off. Choose heat, cool or auto first."))
                    HvacMode.HEAT -> listOf(l.targetTemp to num(command.heat ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("In heat mode only the heat setpoint applies."))))
                    HvacMode.COOL -> listOf(l.targetTemp to num(command.cool ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("In cool mode only the cool setpoint applies."))))
                    HvacMode.AUTO -> listOfNotNull(
                        command.heat?.let { (l.heatThreshold ?: return PatchbayResult.Err(PatchbayError.Unsupported("no heat threshold on this thermostat"))) to num(it) },
                        command.cool?.let { (l.coolThreshold ?: return PatchbayResult.Err(PatchbayError.Unsupported("no cool threshold on this thermostat"))) to num(it) },
                    )
                }
            }
            else -> return PatchbayResult.Err(PatchbayError.Unsupported("thermostats don't support ${command::class.simpleName}"))
        }
        if (writes.any { !it.first.writable }) return PatchbayResult.Err(PatchbayError.Unsupported("that setting is read-only on this thermostat"))
        val resp = link.conn.request("PUT", "/characteristics", HapConnection.JSON, HapDb.writeBody(writes))
        return if (HapDb.writeSucceeded(resp.status, resp.body)) PatchbayResult.Ok(Unit)
        else PatchbayResult.Err(PatchbayError.Unsupported("the thermostat refused that change (HTTP ${resp.status})"))
    }

    private fun currentMode(link: Link): HvacMode? {
        val resp = link.conn.request("GET", HapDb.readUrl(listOf(link.layout.targetState)), accept = HapConnection.JSON)
        val v = HapDb.parseValues(resp.body)?.get(link.layout.targetState.id) ?: return null
        return HapDb.asDouble(v)?.toInt()?.let(ThermostatLayout::modeOf)
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val last = lastContact[device]?.let(::EpochMillis)
        return when {
            device in trustBroken -> AdapterHealth(HealthState.TRUST_CHANGED, last, "${k.name} presented a different key than the one you paired")
            k.keys == null -> AdapterHealth(HealthState.UNPAIRED, last, null)
            links.containsKey(device) -> AdapterHealth(HealthState.OK, last, null)
            else -> AdapterHealth(HealthState.UNREACHABLE, last, null)
        }
    }

    /** Closes open sessions (tests, shutdown). */
    public fun close() { links.keys.toList().forEach(::drop) }

    public companion object {
        public val ID: AdapterId = AdapterId("homekit")
        public const val CATEGORY_THERMOSTAT: Int = 9
        public const val DEFAULT_PORT: Int = 80

        /** HAP forbids these trivial codes. */
        private val FORBIDDEN_CODES = setOf("00000000", "11111111", "22222222", "33333333", "44444444", "55555555", "66666666", "77777777", "88888888", "99999999", "12345678", "87654321")

        /** Shown until the first connection reveals the real ranges. */
        public val DEFAULT_CAPABILITIES: List<Capability> = listOf(
            Capability.Thermostat(setOf(HvacMode.OFF, HvacMode.HEAT, HvacMode.COOL, HvacMode.AUTO), Celsius(7.0)..Celsius(32.0), Celsius(15.0)..Celsius(35.0), minimumDeadband = 1.0),
        )
    }
}
