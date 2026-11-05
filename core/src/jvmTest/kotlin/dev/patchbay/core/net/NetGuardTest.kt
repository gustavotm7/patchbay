package dev.patchbay.core.net

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetGuardTest {

    private val guard = NetGuard()

    @Test
    fun refusesPublicAndCountsDenials() {
        val r = guard.address("8.8.8.8")
        val err = r.errorOrNull()
        assertIs<PatchbayError.NetGuardDenied>(err)
        assertEquals("8.8.8.8", err.address)
        assertEquals(1, guard.stats.value.denied)
        assertEquals("8.8.8.8", guard.stats.value.lastDenied)
    }

    @Test
    fun refusesHostnamesOutright() {
        // No DNS is ever performed: a name is simply not an address.
        assertIs<PatchbayError.NetGuardDenied>(guard.address("localhost").errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(guard.address("printer.local").errorOrNull())
    }

    @Test
    fun tcpConnectToLoopback() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            thread { server.accept().use { it.getOutputStream().write(42) } }
            val addr = guard.address("127.0.0.1").getOrNull()!!
            val s = guard.connectTcp(addr, server.localPort).getOrNull()
            assertNotNull(s)
            s.use { assertEquals(42, it.getInputStream().read()) }
            assertEquals(1, guard.stats.value.allowed)
        }
    }

    @Test
    fun tcpRejectsBadPort() = runBlocking {
        val addr = guard.address("127.0.0.1").getOrNull()!!
        assertIs<PatchbayError.InvalidArgument>(guard.connectTcp(addr, 0).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(guard.connectTcp(addr, 70_000).errorOrNull())
    }

    @Test
    fun udpRoundTripOnLoopback() = runBlocking {
        val a = guard.openUdp(0, bindLoopbackOnly = true).getOrNull()!!
        val b = guard.openUdp(0, bindLoopbackOnly = true).getOrNull()!!
        a.use {
            b.use {
                val lo = guard.address("127.0.0.1").getOrNull()!!
                assertTrue(a.send(lo, b.localPort, "ping".encodeToByteArray()) is PatchbayResult.Ok)
                val d = b.receive(timeoutMs = 2_000).getOrNull()
                assertNotNull(d)
                assertEquals("ping", d.payload.decodeToString())
                assertEquals(a.localPort, d.fromPort)
                assertEquals("127.0.0.1", d.from.literal)
            }
        }
    }

    @Test
    fun receiveTimesOutWithNull() = runBlocking {
        guard.openUdp(0, bindLoopbackOnly = true).getOrNull()!!.use { s ->
            assertNull(s.receive(timeoutMs = 50).getOrNull())
        }
    }

    @Test
    fun multicastOnlyForDiscoveryGroups() {
        assertIs<PatchbayError.NetGuardDenied>(guard.openMulticast("239.1.2.3", 5000).errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(guard.openMulticast("192.168.1.1", 5000).errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(guard.openMulticast("8.8.8.8", 5000).errorOrNull())
    }
}
