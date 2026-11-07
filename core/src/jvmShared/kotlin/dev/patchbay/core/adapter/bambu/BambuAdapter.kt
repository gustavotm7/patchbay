package dev.patchbay.core.adapter.bambu

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.discovery.Ssdp
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.net.mqtt.MqttClient
import dev.patchbay.core.net.tls.PinnedTls
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * Bambu Lab printers over LAN MQTT (docs/protocols/bambu.md): TLS on 8883 with the printer's key pinned on
 * first use, user `bblp`, password = the LAN access code shown on the printer. The printer needs LAN Only
 * Mode + Developer Mode.
 *
 * **Scope (brief §4): monitoring, pause/resume/stop and the chamber light.** Nothing else can be sent:
 * [BambuReport] has no builder for heating, motion or G-code.
 */
public class BambuAdapter(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter, AutoCloseable {

    public data class Config(
        val ssdpTargets: List<String> = listOf(Ssdp.GROUP_V4),
        /** Bambu printers answer/announce on non-standard SSDP ports; we ask on both. */
        val ssdpPorts: List<Int> = listOf(Ssdp.PORT, 1990),
        /** Many printers only broadcast NOTIFY to this port (Bambu Studio listens here). 0 = don't listen. */
        val notifyPort: Int = 2021,
        val mqttPort: Int = BambuReport.MQTT_PORT,
        val scanWindowMs: Long = 2_500,
        val connectTimeoutMs: Int = 5_000,
        /** MQTT keepalive is 60 s; we ping when nothing has arrived for this long. */
        val idleMs: Int = 20_000,
        val minEmitGapMs: Long = 500,
        val maxBackoffMs: Long = 60_000,
        val bindLoopbackOnly: Boolean = false,
    )

    override val id: AdapterId = ID
    override val systemName: String = "Bambu printers"
    override val discoveryNote: String = "Needs LAN Only Mode on. If a scan can't see it (VLANs, client isolation), use Add by address with the IP and serial."
    override val requiresPairing: Boolean = true

    private class Known(val address: LanAddress, val serial: String, val accessCode: String?, val pin: String?)

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val sessions = ConcurrentHashMap<DeviceId, MqttClient>()
    private val latest = ConcurrentHashMap<DeviceId, JsonObject>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val trustBroken = ConcurrentHashMap.newKeySet<DeviceId>()

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        suspend fun listen(sock: dev.patchbay.core.net.GuardedUdp) {
            val deadline = System.nanoTime() + config.scanWindowMs * 1_000_000
            while (currentCoroutineContext().isActive) {
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) break
                val d = (sock.receive(4_096, left) as? PatchbayResult.Ok)?.value ?: continue
                val r = (Ssdp.parse(d.payload) as? PatchbayResult.Ok)?.value ?: continue
                if (r.st?.contains("bambulab-com") != true) continue
                val serial = r.usn?.trim()?.uppercase()?.takeIf(BambuReport::isSerial) ?: continue
                if (!seen.add(serial)) continue
                send(
                    DiscoveredDevice(
                        adapter = ID, key = serial, address = d.from.literal, port = config.mqttPort, kind = DeviceKind.PRINTER_3D,
                        vendor = "Bambu Lab", model = r.headers["devmodel.bambu.com"]?.take(16) ?: "printer",
                        displayName = r.headers["devname.bambu.com"]?.take(48),
                        controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
                    ),
                )
            }
        }
        // 1. Ask (M-SEARCH) and listen for replies.
        val search = (netGuard.openUdp(0, config.bindLoopbackOnly) as? PatchbayResult.Ok)?.value
        // 2. Also listen for the printer's periodic NOTIFY broadcasts. If the port is busy (Bambu Studio on this PC), skip.
        val notify = if (config.notifyPort > 0) (netGuard.openUdp(config.notifyPort, config.bindLoopbackOnly) as? PatchbayResult.Ok)?.value else null
        // Each listener owns and closes its socket, including on cancellation.
        search?.let { s ->
            launch {
                s.use {
                    for (t in config.ssdpTargets) for (port in config.ssdpPorts) {
                        val a = (netGuard.address(t) as? PatchbayResult.Ok)?.value ?: continue
                        it.send(a, port, Ssdp.search(BambuReport.SSDP_ST))
                    }
                    listen(it)
                }
            }
        }
        notify?.let { n -> launch { n.use { listen(it) } } }
    }

    override val manualAdd: dev.patchbay.core.adapter.ManualAdd =
        dev.patchbay.core.adapter.ManualAdd(ID, "Bambu printer", "Serial number (printer screen → Settings → Device)")

    /** For when SSDP is blocked (VLANs, client isolation). The serial must be well-formed; the address must be on the LAN. */
    override fun manualDevice(address: String, key: String, choice: String?): PatchbayResult<DiscoveredDevice> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val serial = key.trim().uppercase()
        if (!BambuReport.isSerial(serial)) return PatchbayResult.Err(PatchbayError.InvalidArgument("That doesn't look like a Bambu serial number"))
        return PatchbayResult.Ok(
            DiscoveredDevice(
                adapter = ID, key = serial, address = a.literal, port = config.mqttPort, kind = DeviceKind.PRINTER_3D,
                vendor = "Bambu Lab", model = "printer", displayName = null,
                controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
            ),
        )
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("bambu-${found.key.lowercase()}")

    override fun capabilities(device: DeviceId): List<Capability> {
        val p = latest[device]
        val nozzles = ((p?.get("device") as? JsonObject)?.get("extruder") as? JsonObject)?.get("info").let { (it as? JsonArray)?.size } ?: 1
        val units = ((p?.get("ams") as? JsonObject)?.get("ams") as? JsonArray)?.size ?: 1
        return listOf(
            Capability.PrintJob,
            Capability.PrintControl(setOf(PrintAction.PAUSE, PrintAction.RESUME, PrintAction.STOP)),
            Capability.PrinterThermals(nozzles = nozzles.coerceIn(1, 4), hasChamberSensor = true),
            Capability.FilamentSlots(units = units.coerceIn(1, 8), slotsPerUnit = 4),
            Capability.AuxLight("Chamber light"),
        )
    }

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val serial = device.value.removePrefix("bambu-").uppercase()
        if (!BambuReport.isSerial(serial)) return PatchbayResult.Err(PatchbayError.InvalidArgument("bad printer id"))
        known[device] = Known(a, serial, pairing?.secret?.decodeToString(), pairing?.pinnedFingerprint)
        sessions.remove(device)?.close()
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val id = deviceId(device)
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val name = device.displayName ?: "Bambu ${device.model}"
        val tls = when (val r = netGuard.connectTls(addr, config.mqttPort, pinnedSha256 = null, deviceName = name, timeoutMs = config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        if (!prompt.confirmFingerprint(name, PinnedTls.display(tls.spkiSha256))) {
            tls.close()
            return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        }
        val code = prompt.askForCode("Access code (printer screen → Settings → LAN Only Mode)", 8..8)
        if (code == null) {
            tls.close()
            return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            when (val m = MqttClient.connect(tls.socket, clientId(), BambuReport.USERNAME, code)) {
                is PatchbayResult.Ok -> { m.value.close(); PatchbayResult.Ok(Pairing(id, code.encodeToByteArray(), tls.spkiSha256)) }
                is PatchbayResult.Err -> { tls.close(); m }
            }
        }
    }

    /** Streams snapshots: connect → subscribe → pushall → merge every report. Reconnects with back-off. */
    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val m = connect(device)
            if (m is PatchbayResult.Err) {
                failures++
                emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                backoff = min(backoff * 2, config.maxBackoffMs)
                delay(backoff)
                continue
            }
            val client = (m as PatchbayResult.Ok).value
            failures = 0; backoff = 2_000L
            val k = known[device]!!
            var lastEmit = 0L
            var idleStrikes = 0
            while (currentCoroutineContext().isActive) {
                when (val r = client.receive()) {
                    is PatchbayResult.Ok -> {
                        val pkt = r.value ?: break // closed
                        idleStrikes = 0
                        if (pkt is MqttClient.Packet.Publish && pkt.topic == BambuReport.reportTopic(k.serial)) {
                            val upd = (BambuReport.parse(pkt.payload) as? PatchbayResult.Ok)?.value ?: continue
                            val state = BambuReport.merge(latest[device] ?: JsonObject(emptyMap()), upd)
                            latest[device] = state
                            lastContact[device] = clock()
                            if (clock() - lastEmit >= config.minEmitGapMs) {
                                lastEmit = clock()
                                emit(DeviceSnapshot(device, Reachability.ONLINE, BambuReport.toStates(state), EpochMillis(clock())))
                            }
                        }
                    }
                    is PatchbayResult.Err -> {
                        if (r.error is PatchbayError.Timeout && ++idleStrikes < 3) { client.ping(); continue }
                        break
                    }
                }
            }
            sessions.remove(device)?.close()
            emit(DeviceSnapshot(device, Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun connect(device: DeviceId): PatchbayResult<MqttClient> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown printer"))
        val code = k.accessCode ?: return PatchbayResult.Err(PatchbayError.Unauthorized("printer not paired"))
        val tls = when (val r = netGuard.connectTls(k.address, config.mqttPort, k.pin, "printer", config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> {
                if (r.error is PatchbayError.TrustChanged) trustBroken += device
                return r
            }
        }
        trustBroken -= device
        tls.socket.soTimeout = config.idleMs
        val m = when (val r = MqttClient.connect(tls.socket, clientId(), BambuReport.USERNAME, code)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { tls.close(); return r }
        }
        if (m.subscribe(BambuReport.reportTopic(k.serial)) is PatchbayResult.Err || m.publish(BambuReport.requestTopic(k.serial), BambuReport.pushAll) is PatchbayResult.Err) {
            m.close()
            return PatchbayResult.Err(PatchbayError.Unreachable("printer closed the connection"))
        }
        sessions[device] = m
        return PatchbayResult.Ok(m)
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown printer"))
        val m = sessions[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("not connected to the printer yet"))
        val topic = BambuReport.requestTopic(k.serial)
        return when (command) {
            is Command.ControlPrint -> m.publish(topic, BambuReport.control(command.action))
            is Command.SetAuxLight -> {
                val r = m.publish(topic, BambuReport.light("chamber_light", command.on))
                // The H2D has a second chamber light; switch both if the printer reports one.
                val hasSecond = (latest[device]?.get("lights_report") as? JsonArray)?.any { it.toString().contains("chamber_light2") } == true
                if (hasSecond) m.publish(topic, BambuReport.light("chamber_light2", command.on)) else r
            }
            else -> PatchbayResult.Err(PatchbayError.Unsupported("printers only accept pause, resume, stop and light commands here"))
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val last = lastContact[device]?.let(::EpochMillis)
        return when {
            k.accessCode == null -> AdapterHealth(HealthState.UNPAIRED, null, "pair to monitor")
            device in trustBroken -> AdapterHealth(HealthState.TRUST_CHANGED, last, "printer key changed")
            sessions[device] != null -> AdapterHealth(HealthState.OK, last, null)
            else -> AdapterHealth(HealthState.UNREACHABLE, last, null)
        }
    }

    private fun clientId(): String = "patchbay-" + Random.nextInt(0x10000, 0xFFFFF).toString(16)

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    public companion object {
        public val ID: AdapterId = AdapterId("bambu-lan")
    }
}
