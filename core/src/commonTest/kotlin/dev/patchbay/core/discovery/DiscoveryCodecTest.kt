package dev.patchbay.core.discovery

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hand-built DNS messages: a realistic Cast-style mDNS response with name compression. */
object DnsFixtures {
    private fun name(vararg labels: String): ByteArray =
        labels.flatMap { l -> listOf(l.length.toByte()) + l.encodeToByteArray().toList() }.toByteArray() + 0

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun ptr(offset: Int) = byteArrayOf((0xC0 or (offset shr 8)).toByte(), offset.toByte())

    /** Response: PTR _googlecast._tcp.local -> Living-TV._googlecast..., SRV, TXT, A. */
    fun castResponse(): ByteArray {
        val header = u16(0) + u16(0x8400) + u16(0) + u16(4) + u16(0) + u16(0)
        val svc = name("_googlecast", "_tcp", "local") // at offset 12
        val svcOff = 12
        var msg = header + svc + u16(Dns.TYPE_PTR) + u16(1) + u32(120)
        val instance = byteArrayOf(9) + "Living-TV".encodeToByteArray() + ptr(svcOff)
        msg += u16(instance.size) + instance
        val instOff = header.size + svc.size + 10 // type, class, ttl, rdlength
        // SRV
        val host = byteArrayOf(8) + "tv-12345".encodeToByteArray() + ptr(svcOff + 17) // "local" label offset
        val srvData = u16(0) + u16(0) + u16(8009) + host
        msg += ptr(instOff) + u16(Dns.TYPE_SRV) + u16(0x8001) + u32(120) + u16(srvData.size) + srvData
        // TXT
        val entries = listOf("md=Chromecast", "fn=Living TV", "id=abc")
        val txt = entries.flatMap { listOf(it.length.toByte()) + it.encodeToByteArray().toList() }.toByteArray()
        msg += ptr(instOff) + u16(Dns.TYPE_TXT) + u16(0x8001) + u32(4500) + u16(txt.size) + txt
        // A for host (uncompressed for simplicity)
        msg += name("tv-12345", "local") + u16(Dns.TYPE_A) + u16(0x8001) + u32(120) + u16(4) + byteArrayOf(192.toByte(), 168.toByte(), 1, 30)
        return msg
    }
}

class DnsTest {
    @Test
    fun queryEncodesPtrQuestions() {
        val q = Dns.ptrQuery(0x1234, listOf("_googlecast._tcp.local", "_hap._tcp.local"))
        val m = Dns.parse(q).getOrNull()!!
        assertEquals(0x1234, m.id)
        assertEquals(false, m.isResponse)
        assertEquals(listOf("_googlecast._tcp.local", "_hap._tcp.local"), m.questions)
    }

    @Test
    fun parsesCompressedResponse() {
        val m = Dns.parse(DnsFixtures.castResponse()).getOrNull()!!
        assertTrue(m.isResponse)
        val ptr = m.records.filterIsInstance<Dns.Record.Ptr>().single()
        assertEquals("_googlecast._tcp.local", ptr.name)
        assertEquals("Living-TV._googlecast._tcp.local", ptr.target)
        val srv = m.records.filterIsInstance<Dns.Record.Srv>().single()
        assertEquals(8009, srv.port)
        assertEquals("tv-12345.local", srv.target)
        val txt = m.records.filterIsInstance<Dns.Record.Txt>().single()
        assertEquals("Chromecast", txt.entries["md"])
        assertEquals("Living TV", txt.entries["fn"])
        val a = m.records.filterIsInstance<Dns.Record.A>().single()
        assertEquals(30, a.address[3].toInt())
    }

    @Test
    fun rejectsPointerLoopsAndForwardPointers() {
        // header + a name that is a pointer to itself (offset 12)
        val self = ByteArray(12).also { it[5] = 1 } + byteArrayOf(0xC0.toByte(), 12) + byteArrayOf(0, 1, 0, 1)
        assertIs<PatchbayError.ProtocolViolation>(Dns.parse(self).errorOrNull())
        val forward = ByteArray(12).also { it[5] = 1 } + byteArrayOf(0xC0.toByte(), 40) + byteArrayOf(0, 1, 0, 1)
        assertIs<PatchbayError.ProtocolViolation>(Dns.parse(forward).errorOrNull())
    }

    @Test
    fun rejectsTruncationAndAbsurdCounts() {
        val r = DnsFixtures.castResponse()
        for (cut in listOf(11, 20, 40, r.size - 1)) assertIs<PatchbayError.ProtocolViolation>(Dns.parse(r.copyOf(cut)).errorOrNull(), "cut $cut")
        val huge = ByteArray(12).also { it[6] = 0x7F; it[7] = 0xFF.toByte() }
        assertIs<PatchbayError.ProtocolViolation>(Dns.parse(huge).errorOrNull())
    }

    @Test
    fun neverThrowsOnMutatedInput() {
        val seed = DnsFixtures.castResponse()
        val rnd = Random(0xD45)
        repeat(30_000) { i ->
            val b = if (i % 4 == 0) ByteArray(rnd.nextInt(0, 400)) { rnd.nextInt().toByte() } else seed.copyOf().also { s ->
                repeat(rnd.nextInt(1, 8)) { s[rnd.nextInt(s.size)] = rnd.nextInt().toByte() }
            }
            Dns.parse(b)
        }
    }
}

class SsdpTest {
    @Test
    fun searchRequestIsWellFormed() {
        val s = Ssdp.search().decodeToString()
        assertTrue(s.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("MAN: \"ssdp:discover\"\r\n" in s && s.endsWith("\r\n\r\n"))
    }

    @Test
    fun parsesSearchResponse() {
        val r = Ssdp.parse(
            ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.40:1990/desc.xml\r\n" +
                "SERVER: WebOS/4.1.0 UPnP/1.0\r\nST: urn:lge-com:service:webos-second-screen:1\r\nUSN: uuid:abc::urn:lge\r\n\r\n").encodeToByteArray(),
        ).getOrNull()!!
        assertEquals(Ssdp.Reply.Kind.SEARCH_RESPONSE, r.kind)
        assertEquals("WebOS/4.1.0 UPnP/1.0", r.server)
        assertEquals("urn:lge-com:service:webos-second-screen:1", r.st)
    }

    @Test
    fun rejectsGarbage() {
        listOf("", "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\nno colon here\r\n\r\n", "HTTP/1.1 200 OK\r\nbad name: x\r\n\r\n")
            .forEach { assertIs<PatchbayError.ProtocolViolation>(Ssdp.parse(it.encodeToByteArray()).errorOrNull(), it) }
        assertIs<PatchbayResult.Err>(Ssdp.parse(ByteArray(5_000) { 'a'.code.toByte() }))
    }
}
