package dev.patchbay.core.net

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import dev.patchbay.core.net.tls.PinnedTls

/**
 * An address NetGuard has approved. The constructor is internal: the only way to get one is
 * [NetGuard.address], which runs [AddressPolicy]. Every socket API below takes a [LanAddress], so
 * "connect to an unchecked address" doesn't type-check.
 *
 * There is deliberately **no hostname constructor**. Resolving a name can itself send a DNS query to
 * the internet, so devices are addressed by the IP literals that discovery produced.
 */
public class LanAddress internal constructor(internal val inet: InetAddress, public val verdict: AddressPolicy.Verdict) {
    public val literal: String get() = inet.hostAddress ?: "?"
    override fun equals(other: Any?): Boolean = other is LanAddress && other.inet == inet
    override fun hashCode(): Int = inet.hashCode()
    override fun toString(): String = literal
}

/**
 * The single door to the network (privacy invariant 1). Created once by Bootstrap and shared.
 * Everything here is LAN-only by construction. `tools/check-policy.sh` (NET001) fails the build if
 * code outside this package opens sockets.
 */
public class NetGuard {

    private val _stats = MutableStateFlow(NetGuardStats())
    public val stats: StateFlow<NetGuardStats> = _stats.asStateFlow()

    /** Approves an IP literal (from config, discovery or user input). */
    public fun address(literal: String): PatchbayResult<LanAddress> {
        val bytes = IpLiteral.parse(literal) ?: return deny(literal, "not an IP literal")
        return address(bytes, literal)
    }

    /** Approves raw address bytes (e.g. the source of a received datagram). */
    public fun address(bytes: ByteArray): PatchbayResult<LanAddress> = address(bytes, null)

    private fun address(bytes: ByteArray, display: String?): PatchbayResult<LanAddress> {
        val verdict = AddressPolicy.check(bytes)
        // getByAddress never performs a DNS lookup.
        val inet = InetAddress.getByAddress(bytes)
        if (!verdict.allowed) return deny(display ?: (inet.hostAddress ?: "?"), verdict.name)
        return PatchbayResult.Ok(LanAddress(inet, verdict))
    }

    /** Opens a TCP connection. Never uses a configured SOCKS/HTTP proxy. */
    public suspend fun connectTcp(to: LanAddress, port: Int, timeoutMs: Int = 3_000): PatchbayResult<Socket> {
        if (port !in 1..65_535) return PatchbayResult.Err(PatchbayError.InvalidArgument("port out of range"))
        return withContext(Dispatchers.IO) {
            val socket = Socket(Proxy.NO_PROXY)
            try {
                socket.connect(InetSocketAddress(to.inet, port), timeoutMs)
                socket.soTimeout = timeoutMs
                allow()
                PatchbayResult.Ok(socket)
            } catch (e: SocketTimeoutException) {
                socket.close()
                PatchbayResult.Err(PatchbayError.Timeout("connect to ${to.literal}:$port timed out"))
            } catch (e: IOException) {
                socket.close()
                PatchbayResult.Err(PatchbayError.Unreachable("connect to ${to.literal}:$port failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    /**
     * Opens a TLS connection with device trust per ADR 0005. With [pinnedSha256] null (pairing), any key is
     * accepted **for this handshake only** and returned in [TlsConnection.spkiSha256] with `firstUse = true`.
     * The caller must show that fingerprint to the user and get confirmation before sending any credential.
     * With a pin, a different key fails with [PatchbayError.TrustChanged].
     */
    public suspend fun connectTls(
        to: LanAddress,
        port: Int,
        pinnedSha256: String?,
        deviceName: String,
        timeoutMs: Int = 5_000,
    ): PatchbayResult<TlsConnection> {
        val tcp = when (val r = connectTcp(to, port, timeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        return withContext(Dispatchers.IO) {
            val observed = PinnedTls.ObservedKey()
            try {
                val ctx = PinnedTls.context(pinnedSha256, observed)
                val ssl = ctx.socketFactory.createSocket(tcp, to.literal, port, true) as SSLSocket
                PinnedTls.harden(ssl)
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                PatchbayResult.Ok(TlsConnection(ssl, observed.spkiSha256 ?: "", firstUse = pinnedSha256 == null))
            } catch (e: IOException) {
                tcp.close()
                val mismatch = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<PinnedTls.PinMismatch>().firstOrNull()
                if (mismatch != null) {
                    PatchbayResult.Err(PatchbayError.TrustChanged(deviceName, "$deviceName presented a different key than the one you paired"))
                } else {
                    PatchbayResult.Err(PatchbayError.Unreachable("TLS to ${to.literal}:$port failed: ${e.javaClass.simpleName}"))
                }
            }
        }
    }

    /**
     * Opens a UDP socket bound to [bindPort] on all local addresses (0 = ephemeral). Binding is local and
     * not a destination; every send is checked.
     */
    public fun openUdp(bindPort: Int = 0, bindLoopbackOnly: Boolean = false): PatchbayResult<GuardedUdp> {
        if (bindPort !in 0..65_535) return PatchbayResult.Err(PatchbayError.InvalidArgument("port out of range"))
        return try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(if (bindLoopbackOnly) InetSocketAddress(InetAddress.getLoopbackAddress(), bindPort) else InetSocketAddress(bindPort))
            PatchbayResult.Ok(GuardedUdp(s, this))
        } catch (e: IOException) {
            PatchbayResult.Err(PatchbayError.Unreachable("cannot bind UDP port $bindPort: ${e.javaClass.simpleName}"))
        }
    }

    /**
     * Opens a multicast socket joined to one of the [AddressPolicy] discovery groups. Joining any other
     * group is refused. On Android, the caller must hold a WifiManager.MulticastLock (LocalNetworkAccess).
     */
    public fun openMulticast(group: String, port: Int, iface: NetworkInterface? = null): PatchbayResult<GuardedUdp> {
        val addr = when (val r = address(group)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        if (addr.verdict != AddressPolicy.Verdict.DISCOVERY_MULTICAST) return deny(group, "not a discovery group")
        return try {
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.joinGroup(InetSocketAddress(addr.inet, port), iface)
            PatchbayResult.Ok(GuardedUdp(s, this))
        } catch (e: IOException) {
            PatchbayResult.Err(PatchbayError.Unreachable("cannot join $group:$port: ${e.javaClass.simpleName}"))
        }
    }

    internal fun allow() {
        _stats.update { it.copy(allowed = it.allowed + 1) }
    }

    internal fun droppedInbound() {
        _stats.update { it.copy(droppedInbound = it.droppedInbound + 1) }
    }

    private fun deny(what: String, why: String): PatchbayResult<Nothing> {
        _stats.update { it.copy(denied = it.denied + 1, lastDenied = what) }
        return PatchbayResult.Err(PatchbayError.NetGuardDenied(what, "NetGuard refused $what ($why)"))
    }
}

/** An established, pinned TLS connection. [spkiSha256] is what the device presented. */
public class TlsConnection(public val socket: SSLSocket, public val spkiSha256: String, public val firstUse: Boolean) : AutoCloseable {
    override fun close() {
        socket.close()
    }
}

/** A received datagram from an approved LAN source. [payload] is untrusted. */
public class Datagram(public val from: LanAddress, public val fromPort: Int, public val payload: ByteArray)

/**
 * A UDP socket whose sends are restricted to [LanAddress]es and whose receives drop packets from
 * non-LAN sources (a spoofed public source address never reaches a parser).
 */
public class GuardedUdp internal constructor(private val socket: DatagramSocket, private val guard: NetGuard) : AutoCloseable {

    public val localPort: Int get() = socket.localPort

    public suspend fun send(to: LanAddress, port: Int, payload: ByteArray): PatchbayResult<Unit> {
        if (port !in 1..65_535) return PatchbayResult.Err(PatchbayError.InvalidArgument("port out of range"))
        if (payload.size > MAX_DATAGRAM) return PatchbayResult.Err(PatchbayError.InvalidArgument("datagram too large"))
        return withContext(Dispatchers.IO) {
            try {
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(to.inet, port)))
                guard.allow()
                PatchbayResult.Ok(Unit)
            } catch (e: IOException) {
                PatchbayResult.Err(PatchbayError.Unreachable("send to ${to.literal}:$port failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    /**
     * Waits up to [timeoutMs] for one datagram of at most [maxBytes]. Returns `Ok(null)` on timeout.
     * Oversized datagrams are truncated by the OS; callers' parsers reject truncated input.
     */
    public suspend fun receive(maxBytes: Int = 2_048, timeoutMs: Int = 1_000): PatchbayResult<Datagram?> {
        require(maxBytes in 1..MAX_DATAGRAM && timeoutMs > 0)
        return withContext(Dispatchers.IO) {
            val buf = ByteArray(maxBytes)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMs <= 0) return@withContext PatchbayResult.Ok(null)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.soTimeout = remainingMs
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return@withContext PatchbayResult.Ok(null)
                } catch (e: IOException) {
                    return@withContext if (socket.isClosed) PatchbayResult.Ok(null)
                    else PatchbayResult.Err(PatchbayError.Unreachable("receive failed: ${e.javaClass.simpleName}"))
                }
                val from = guard.address(packet.address.address)
                if (from is PatchbayResult.Ok) {
                    return@withContext PatchbayResult.Ok(Datagram(from.value, packet.port, packet.data.copyOf(packet.length)))
                }
                guard.droppedInbound()
            }
            @Suppress("UNREACHABLE_CODE")
            PatchbayResult.Ok(null)
        }
    }

    public val isClosed: Boolean get() = socket.isClosed

    override fun close() {
        socket.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 65_507
    }
}
