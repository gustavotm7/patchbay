package dev.patchbay.simulators

import dev.patchbay.core.net.GuardedSocketFactory
import dev.patchbay.core.net.NetGuard
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ADR 0008: a third-party player gets sockets that can only reach LAN addresses, with no DNS and no proxy. */
class GuardedSocketFactoryTest {

    private val guard = NetGuard()
    private val factory = GuardedSocketFactory(guard)
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.40")).also { s ->
        thread(isDaemon = true) { while (!s.isClosed) { try { s.accept().use { it.getOutputStream().write(42) } } catch (e: IOException) { return@thread } } }
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun connectsToLanAddressesThroughEveryOverloadAndCountsThem() {
        val before = guard.stats.value.allowed
        factory.createSocket("127.0.0.40", server.localPort).use { assertEquals(42, it.getInputStream().read()) }
        factory.createSocket(InetAddress.getByName("127.0.0.40"), server.localPort).use { assertEquals(42, it.getInputStream().read()) }
        factory.createSocket().use { s -> s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.40"), server.localPort), 1_000); assertEquals(42, s.getInputStream().read()) }
        assertEquals(before + 3, guard.stats.value.allowed)
    }

    @Test
    fun refusesPublicAddressesEverywhere() {
        val publicIp = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        assertFailsWith<IOException> { factory.createSocket("8.8.8.8", 554) }
        assertFailsWith<IOException> { factory.createSocket(publicIp, 554) }
        assertFailsWith<IOException> { factory.createSocket().connect(InetSocketAddress(publicIp, 554), 500) }
        assertTrue(guard.stats.value.denied >= 1)
    }

    @Test
    fun refusesHostnamesAndUnresolvedAddressesWithoutAnyLookup() {
        assertFailsWith<IOException> { factory.createSocket("camera.example.com", 554) }
        assertFailsWith<IOException> { factory.createSocket("localhost", 554) } // a name, not a literal
        assertFailsWith<IOException> { factory.createSocket().connect(InetSocketAddress.createUnresolved("camera.example.com", 554), 500) }
    }
}
