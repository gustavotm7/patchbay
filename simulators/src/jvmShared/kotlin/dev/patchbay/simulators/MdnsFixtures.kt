package dev.patchbay.simulators

import dev.patchbay.core.discovery.Dns

/** Hand-built DNS messages: a realistic Cast-style mDNS response with name compression. */
public object MdnsFixtures {
    private fun name(vararg labels: String): ByteArray =
        labels.flatMap { l -> listOf(l.length.toByte()) + l.encodeToByteArray().toList() }.toByteArray() + 0

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun ptr(offset: Int) = byteArrayOf((0xC0 or (offset shr 8)).toByte(), offset.toByte())

    /** Response: PTR _googlecast._tcp.local -> Living-TV._googlecast..., SRV, TXT, A. */
    public fun castResponse(): ByteArray {
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
