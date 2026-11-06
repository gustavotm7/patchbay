package dev.patchbay.core.net

import dev.patchbay.core.result.PatchbayResult
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * A [SocketFactory] for third-party players (ADR 0008) that can only reach LAN addresses. Every socket it makes
 * is a [GuardedSocket]: it never uses a proxy, never does a DNS lookup (hostnames and unresolved addresses are
 * refused), and checks the destination against [AddressPolicy] at connect time, whichever overload the library calls.
 * Counted in [NetGuardStats] like every other connection.
 */
public class GuardedSocketFactory(private val guard: NetGuard) : SocketFactory() {

    override fun createSocket(): Socket = GuardedSocket(guard)

    override fun createSocket(host: String, port: Int): Socket =
        GuardedSocket(guard).also { it.connect(literal(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        GuardedSocket(guard).also { it.bind(InetSocketAddress(localHost, localPort)); it.connect(literal(host, port)) }

    override fun createSocket(address: InetAddress, port: Int): Socket =
        GuardedSocket(guard).also { it.connect(InetSocketAddress(address, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        GuardedSocket(guard).also { it.bind(InetSocketAddress(localAddress, localPort)); it.connect(InetSocketAddress(address, port)) }

    private fun literal(host: String, port: Int): SocketAddress = when (val r = guard.address(host)) {
        is PatchbayResult.Ok -> InetSocketAddress(r.value.inet, port) // already an IP, so no lookup happens
        is PatchbayResult.Err -> throw IOException(r.error.message)
    }
}

/** A TCP socket that refuses non-LAN and unresolved destinations. Direct connections only (no proxy). */
internal class GuardedSocket(private val guard: NetGuard) : Socket(Proxy.NO_PROXY) {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val e = endpoint as? InetSocketAddress ?: throw IOException("unsupported socket address")
        if (e.isUnresolved) throw IOException("hostnames are not allowed; Patchbay never uses DNS")
        val verdict = guard.address(e.address.address)
        if (verdict is PatchbayResult.Err) throw IOException(verdict.error.message)
        super.connect(e, timeout)
        guard.allow()
    }
}
