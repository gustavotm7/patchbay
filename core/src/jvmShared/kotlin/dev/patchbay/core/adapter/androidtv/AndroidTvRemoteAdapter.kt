package dev.patchbay.core.adapter.androidtv

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
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
import dev.patchbay.core.net.TlsConnection
import dev.patchbay.core.net.tls.ClientIdentity
import dev.patchbay.core.net.tls.PinnedTls
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Google TV, Android TV and Chromecast with Google TV as a remote control (Android TV Remote v2): TLS on 6467 for pairing (the TV shows
 * a 6-character code) and 6466 for the remote session, with a client certificate we generate and keep, and the TV's key pinned on first
 * use (ADR 0005). Only remote keys are sent: d-pad, home, back, volume, mute, play/pause, power. Nothing is installed, launched or read.
 * Message formats come from the reference projects (see [AndroidTvProtocol]). It is found by the network census (mDNS).
 */
public class AndroidTvRemoteAdapter(
    private val netGuard: NetGuard,
    private val finder: CastAdapter.CastFinder,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter {

    public data class Config(
        val pairingPort: Int = AndroidTvProtocol.PAIRING_PORT,
        val remotePort: Int = AndroidTvProtocol.REMOTE_PORT,
        val connectTimeoutMs: Int = 5_000,
        /** The TV pings about every 5 s; three silent intervals in a row drop the session. */
        val silentMs: Int = 8_000,
        val maxBackoffMs: Long = 60_000,
    )

    override val id: AdapterId = ID
    override fun trustChanged(device: DeviceId): Boolean = device in trustBroken
    override val requiresPairing: Boolean = true
    override val systemName: String = "Google TV remote"
    override val discoveryNote: String = "Google TVs and Android TVs must be on and on the same network. A guest network or a VLAN hides them."

    private class Known(val address: LanAddress, val pin: String?, val identity: ClientIdentity?, val name: String)
    private class Session(val tls: TlsConnection, val out: OutputStream) {
        fun send(body: ByteArray) = synchronized(this) { out.write(AndroidTvProtocol.frame(body)); out.flush() }
    }

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val sessions = ConcurrentHashMap<DeviceId, Session>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val trustBroken = ConcurrentHashMap.newKeySet<DeviceId>()

    @Volatile public var rejectedMessages: Long = 0
        private set

    override fun discover(): Flow<DiscoveredDevice> = flow {
        for (f in finder.find()) {
            if (f.services.none { it.startsWith("_androidtvremote2._tcp") }) continue
            emit(
                DiscoveredDevice(
                    adapter = ID, key = "ip-" + f.address.map { if (it.isLetterOrDigit()) it else '-' }.joinToString(""), address = f.address,
                    port = config.remotePort, kind = DeviceKind.MEDIA_RECEIVER, vendor = "Android TV", model = f.model?.take(32) ?: "Google TV",
                    displayName = ((f.friendlyName?.take(40)?.takeIf { it.isNotBlank() } ?: "TV") + " remote"),
                    controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
                ),
            )
        }
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("atvremote-${found.key.lowercase()}")

    override fun capabilities(device: DeviceId): List<Capability> = listOf(Capability.RemoteKeys)

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        known[device] = Known(a, pairing?.pinnedFingerprint, pairing?.secret?.let(ClientIdentity::fromBytes), known[device]?.name ?: "TV")
        return PatchbayResult.Ok(Unit)
    }

    /** Pairing: confirm the TV's key, run the handshake, type the code the TV shows. The client certificate becomes the stored secret. */
    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val name = device.displayName ?: "TV"
        val identity = withContext(Dispatchers.Default) { ClientIdentity.generate() }
        val tls = when (val r = netGuard.connectTls(addr, config.pairingPort, null, name, config.connectTimeoutMs, identity.keyManagers())) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        try {
            if (!prompt.confirmFingerprint(name, PinnedTls.display(tls.spkiSha256))) return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
            tls.socket.soTimeout = 30_000
            val input = tls.socket.getInputStream()
            val out = tls.socket.getOutputStream()
            fun send(b: ByteArray) { out.write(AndroidTvProtocol.frame(b)); out.flush() }
            suspend fun expect(step: AndroidTvProtocol.PairingStep): PatchbayError? = withContext(Dispatchers.IO) {
                try {
                    when (val r = AndroidTvProtocol.parsePairing(readFrame(input))) {
                        is PatchbayResult.Err -> r.error
                        is PatchbayResult.Ok -> if (r.value == step) null else PatchbayError.ProtocolViolation("androidtv: expected $step but got ${r.value}")
                    }
                } catch (e: SocketTimeoutException) {
                    PatchbayError.Timeout("the TV didn't answer during pairing")
                } catch (e: IOException) {
                    PatchbayError.Unreachable("the TV closed the connection during pairing")
                }
            }
            withContext(Dispatchers.IO) { send(AndroidTvProtocol.pairingRequest("Patchbay")) }
            expect(AndroidTvProtocol.PairingStep.REQUEST_ACK)?.let { return PatchbayResult.Err(it) }
            withContext(Dispatchers.IO) { send(AndroidTvProtocol.pairingOptions()) }
            expect(AndroidTvProtocol.PairingStep.OPTIONS)?.let { return PatchbayResult.Err(it) }
            withContext(Dispatchers.IO) { send(AndroidTvProtocol.pairingConfiguration()) }
            expect(AndroidTvProtocol.PairingStep.CONFIGURATION_ACK)?.let { return PatchbayResult.Err(it) }

            val code = prompt.askForCode("Code shown on the TV (6 letters and numbers)", 6..6)
                ?: return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
            val serverKey = (tls.socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.publicKey as? RSAPublicKey
                ?: return PatchbayResult.Err(PatchbayError.ProtocolViolation("androidtv: the TV didn't present an RSA key"))
            val clientKey = identity.certificate.publicKey as RSAPublicKey
            val secret = AndroidTvProtocol.pairingSecret(
                modulusBytes(clientKey.modulus), exponentBytes(clientKey.publicExponent), modulusBytes(serverKey.modulus), exponentBytes(serverKey.publicExponent),
                code, { MessageDigest.getInstance("SHA-256").digest(it) },
            ) ?: return PatchbayResult.Err(PatchbayError.Unauthorized("That code isn't right. Check the code on the TV and try again."))
            withContext(Dispatchers.IO) { send(AndroidTvProtocol.pairingSecretMessage(secret)) }
            expect(AndroidTvProtocol.PairingStep.SECRET_ACK)?.let { return PatchbayResult.Err(it) }
            val id = deviceId(device)
            known[id] = Known(addr, tls.spkiSha256, identity, name)
            return PatchbayResult.Ok(Pairing(id, identity.toBytes(), tls.spkiSha256))
        } finally {
            tls.close()
        }
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val session = when (val r = connect(device)) {
                is PatchbayResult.Ok -> r.value
                is PatchbayResult.Err -> {
                    failures++
                    emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                    backoff = min(backoff * 2, config.maxBackoffMs)
                    delay(backoff)
                    continue
                }
            }
            failures = 0; backoff = 2_000L
            val input = session.tls.socket.getInputStream()
            var silent = 0
            var announced = false
            try {
                loop@ while (currentCoroutineContext().isActive) {
                    val body = try {
                        readFrame(input)
                    } catch (e: SocketTimeoutException) {
                        if (++silent >= 3) break
                        continue
                    }
                    silent = 0
                    lastContact[device] = clock()
                    when (val r = AndroidTvProtocol.parseRemote(body)) {
                        is PatchbayResult.Err -> rejectedMessages++
                        is PatchbayResult.Ok -> when (val m = r.value) {
                            is AndroidTvProtocol.Incoming.Configure -> session.send(AndroidTvProtocol.remoteConfigure(m.features))
                            is AndroidTvProtocol.Incoming.SetActive -> session.send(AndroidTvProtocol.remoteSetActive(AndroidTvProtocol.FEATURES and m.active))
                            is AndroidTvProtocol.Incoming.Ping -> session.send(AndroidTvProtocol.remotePingResponse(m.val1))
                            is AndroidTvProtocol.Incoming.Started -> if (!announced) {
                                announced = true
                                emit(DeviceSnapshot(device, Reachability.ONLINE, emptyList(), EpochMillis(clock())))
                            }
                            is AndroidTvProtocol.Incoming.Error -> break@loop
                            is AndroidTvProtocol.Incoming.Other -> {}
                        }
                    }
                }
            } catch (e: IOException) {
                // the session ended; reconnect with back-off
            }
            sessions.remove(device)?.tls?.close()
            emit(DeviceSnapshot(device, Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
            delay(backoff)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun connect(device: DeviceId): PatchbayResult<Session> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown TV"))
        val identity = k.identity ?: return PatchbayResult.Err(PatchbayError.Unauthorized("pair this TV first"))
        val tls = when (val r = netGuard.connectTls(k.address, config.remotePort, k.pin, k.name, config.connectTimeoutMs, identity.keyManagers())) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> {
                if (r.error is PatchbayError.TrustChanged) trustBroken += device
                return r
            }
        }
        trustBroken -= device
        tls.socket.soTimeout = config.silentMs
        val s = Session(tls, tls.socket.getOutputStream())
        sessions[device] = s
        return PatchbayResult.Ok(s)
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        if (command !is Command.PressKey) return PatchbayResult.Err(PatchbayError.Unsupported("this device accepts remote keys only"))
        val s = sessions[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("not connected to the TV yet"))
        return try {
            withContext(Dispatchers.IO) { s.send(AndroidTvProtocol.remoteKey(command.key)) }
            PatchbayResult.Ok(Unit)
        } catch (e: IOException) {
            PatchbayResult.Err(PatchbayError.Unreachable("the connection to the TV dropped"))
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val last = lastContact[device]?.let(::EpochMillis)
        return when {
            device in trustBroken -> AdapterHealth(HealthState.TRUST_CHANGED, last, "${k.name} presented a different key than the one you paired")
            sessions.containsKey(device) -> AdapterHealth(HealthState.OK, last, null)
            else -> AdapterHealth(HealthState.UNREACHABLE, last, null)
        }
    }

    /** One varint-length-prefixed message. A bad length ends the session (counted). */
    private fun readFrame(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 21) { rejectedMessages++; throw IOException("bad length") }
        }
        if (len !in 1..AndroidTvProtocol.MAX_MESSAGE_BYTES) { rejectedMessages++; throw IOException("bad length") }
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = input.read(out, off, len - off)
            if (r < 0) throw IOException("closed")
            off += r
        }
        return out
    }

    /** The RSA modulus as unsigned big-endian bytes without a sign byte (what the reference implementations hash). */
    private fun modulusBytes(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }

    /** The public exponent as hex padded to whole bytes (65537 becomes 01 00 01). */
    private fun exponentBytes(e: BigInteger): ByteArray {
        val hex = e.toString(16).let { if (it.length % 2 == 1) "0$it" else it }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    public companion object {
        public val ID: AdapterId = AdapterId("androidtv-remote")
    }
}
