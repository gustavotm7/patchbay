package dev.patchbay.core.discovery

import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkCensusTest {

    /** A one-shot UDP responder bound to a loopback address, answering the first datagram it receives. */
    private fun responder(address: String, reply: (ByteArray) -> ByteArray): DatagramSocket {
        val s = DatagramSocket(InetSocketAddress(InetAddress.getByName(address), 0))
        thread(isDaemon = true) {
            val buf = ByteArray(9000)
            val p = DatagramPacket(buf, buf.size)
            runCatching { s.receive(p) }.onSuccess {
                val out = reply(p.data.copyOf(p.length))
                s.send(DatagramPacket(out, out.size, p.socketAddress))
            }
        }
        return s
    }

    @Test
    fun collectsMdnsAndSsdpByAddress(): Unit = runBlocking {
        var queried: Dns.Message? = null
        val cast = responder("127.0.0.6") { q -> queried = (Dns.parse(q) as PatchbayResult.Ok).value; DnsFixtures.castResponse() }
        val tv = responder("127.0.0.7") {
            ("HTTP/1.1 200 OK\r\nSERVER: WebOS/4.1.0 UPnP/1.0\r\nST: urn:lge-com:service:webos-second-screen:1\r\n" +
                "USN: uuid:x\r\nLOCATION: http://127.0.0.7:1990/\r\n\r\n").toByteArray()
        }
        val census = NetworkCensus(
            NetGuard(),
            NetworkCensus.Config("127.0.0.6", cast.localPort, "127.0.0.7", tv.localPort, windowMs = 700, bindLoopbackOnly = true),
        )
        val found = (census.run() as PatchbayResult.Ok).value
        cast.close(); tv.close()

        assertTrue(queried!!.questions.contains("_googlecast._tcp.local"))
        assertEquals(listOf("127.0.0.6", "127.0.0.7"), found.map { it.address })
        val c = found[0]
        assertEquals("Living TV", c.friendlyName)
        assertEquals("Chromecast", c.model)
        assertTrue("_googlecast._tcp" in c.services)
        assertTrue(8009 in c.ports)
        assertEquals(listOf("Cast: local media control"), c.hints)
        val t = found[1]
        assertEquals("WebOS/4.1.0 UPnP/1.0", t.server)
        assertEquals(listOf("LG webOS TV: local control"), t.hints)
    }

    @Test
    fun malformedRepliesAreIgnoredNotFatal(): Unit = runBlocking {
        val junk = responder("127.0.0.8") { ByteArray(40) { 0xC0.toByte() } }
        val census = NetworkCensus(NetGuard(), NetworkCensus.Config("127.0.0.8", junk.localPort, "127.0.0.8", 9, windowMs = 300, bindLoopbackOnly = true))
        val found = (census.run() as PatchbayResult.Ok).value
        junk.close()
        assertEquals(0, found.size)
    }

    @Test
    fun serviceTypeExtraction() {
        assertEquals("_googlecast._tcp", NetworkCensus.serviceType("Living-TV._googlecast._tcp.local"))
        assertEquals("_hap._tcp", NetworkCensus.serviceType("_hap._tcp.local"))
    }
}
