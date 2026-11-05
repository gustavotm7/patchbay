package dev.patchbay.core.net

import dev.patchbay.core.net.AddressPolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressPolicyTest {

    private fun verdict(s: String) = AddressPolicy.check(s)

    @Test
    fun allowsRfc1918() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.255")
            .forEach { assertEquals(Verdict.PRIVATE_V4, verdict(it), it) }
    }

    @Test
    fun rfc1918Boundaries() {
        assertEquals(Verdict.DENIED_PUBLIC, verdict("172.15.255.255"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("172.32.0.0"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("192.167.255.255"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("192.169.0.0"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("11.0.0.0"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("9.255.255.255"))
    }

    @Test
    fun allowsLinkLocalAndLoopback() {
        assertEquals(Verdict.LINK_LOCAL_V4, verdict("169.254.1.1"))
        assertEquals(Verdict.LOOPBACK, verdict("127.0.0.1"))
        assertEquals(Verdict.LOOPBACK, verdict("127.255.0.9"))
        assertEquals(Verdict.LOOPBACK, verdict("::1"))
        assertEquals(Verdict.LINK_LOCAL_V6, verdict("fe80::1"))
        assertEquals(Verdict.LINK_LOCAL_V6, verdict("febf:ffff::1"))
        assertEquals(Verdict.UNIQUE_LOCAL_V6, verdict("fd12:3456:789a::1"))
        assertEquals(Verdict.UNIQUE_LOCAL_V6, verdict("fc00::"))
    }

    @Test
    fun deniesPublicAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "142.250.1.1", "52.0.0.1", "203.0.113.5", "2001:4860:4860::8888", "2606:4700::1111")
            .forEach { assertFalse(verdict(it).allowed, it) }
    }

    @Test
    fun deniesCgnatAndSpecialRanges() {
        assertEquals(Verdict.DENIED_PUBLIC, verdict("100.64.0.1")) // CGNAT is ISP space, not your LAN
        assertEquals(Verdict.DENIED_SPECIAL, verdict("0.0.0.0"))
        assertEquals(Verdict.DENIED_SPECIAL, verdict("240.0.0.1"))
        assertEquals(Verdict.DENIED_SPECIAL, verdict("::"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("fec0::1")) // deprecated site-local
        assertEquals(Verdict.DENIED_PUBLIC, verdict("fe00::1")) // just outside fe80::/10
        assertEquals(Verdict.DENIED_PUBLIC, verdict("fec0::")) // just above fe80::/10
    }

    @Test
    fun mappedIpv6IsJudgedByEmbeddedIpv4() {
        assertEquals(Verdict.PRIVATE_V4, verdict("::ffff:192.168.1.20"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("::ffff:8.8.8.8"))
        assertEquals(Verdict.DENIED_PUBLIC, verdict("::ffff:808:808"))
    }

    @Test
    fun deniesTunnelAndTranslationPrefixes() {
        assertFalse(verdict("64:ff9b::808:808").allowed) // NAT64 to 8.8.8.8
        assertFalse(verdict("64:ff9b::c0a8:101").allowed) // NAT64 even to a "private" v4 leaves the LAN
        assertFalse(verdict("2002:c0a8:0101::1").allowed) // 6to4
        assertFalse(verdict("2001:0::1").allowed) // Teredo
        assertFalse(verdict("::192.168.1.1").allowed) // deprecated IPv4-compatible
    }

    @Test
    fun multicastOnlyForDiscoveryGroups() {
        assertEquals(Verdict.DISCOVERY_MULTICAST, verdict("224.0.0.251"))
        assertEquals(Verdict.DISCOVERY_MULTICAST, verdict("239.255.255.250"))
        assertEquals(Verdict.DISCOVERY_MULTICAST, verdict("ff02::fb"))
        assertEquals(Verdict.DISCOVERY_MULTICAST, verdict("ff02::c"))
        assertEquals(Verdict.DENIED_MULTICAST, verdict("224.0.0.1"))
        assertEquals(Verdict.DENIED_MULTICAST, verdict("239.1.1.1"))
        assertEquals(Verdict.DENIED_MULTICAST, verdict("ff0e::fb")) // global-scope mDNS group
        assertEquals(Verdict.DENIED_MULTICAST, verdict("ff05::c")) // site-scope SSDP
    }

    @Test
    fun allowsLimitedBroadcastForWakeOnLan() {
        assertEquals(Verdict.LIMITED_BROADCAST, verdict("255.255.255.255"))
    }

    @Test
    fun rejectsMalformedBytes() {
        assertEquals(Verdict.DENIED_MALFORMED, AddressPolicy.check(ByteArray(0)))
        assertEquals(Verdict.DENIED_MALFORMED, AddressPolicy.check(ByteArray(5)))
        assertEquals(Verdict.DENIED_MALFORMED, AddressPolicy.check("not-an-ip"))
    }

    @Test
    fun exhaustiveFirstOctetSweep() {
        // Every first octet: only the expected prefixes may ever be allowed.
        for (b0 in 0..255) {
            for (b1 in listOf(0, 15, 16, 31, 32, 168, 254, 255)) {
                val v = AddressPolicy.check(byteArrayOf(b0.toByte(), b1.toByte(), 1, 1))
                val expected = b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) ||
                    (b0 == 169 && b1 == 254) || b0 == 127
                assertEquals(expected, v.allowed, "$b0.$b1.1.1 -> $v")
            }
        }
    }
}

class IpLiteralTest {

    @Test
    fun parsesIpv4() {
        assertTrue(IpLiteral.parse("192.168.1.1")!!.contentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
    }

    @Test
    fun rejectsAmbiguousIpv4() {
        listOf("010.0.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.-4", "1..2.3", " 1.2.3.4", "1.2.3.4 ", "0x7f.0.0.1", "")
            .forEach { assertNull(IpLiteral.parse(it), it) }
    }

    @Test
    fun parsesIpv6Forms() {
        val full = IpLiteral.parse("fe80:0:0:0:0:0:0:1")!!
        val short = IpLiteral.parse("fe80::1")!!
        assertTrue(full.contentEquals(short))
        assertEquals(16, IpLiteral.parse("::")!!.size)
        assertEquals(16, IpLiteral.parse("1::")!!.size)
        val mapped = IpLiteral.parse("::ffff:10.0.0.1")!!
        assertEquals(10, mapped[12].toInt())
    }

    @Test
    fun rejectsBadIpv6() {
        listOf("1::2::3", "12345::1", "fe80::1%eth0", "[fe80::1]", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "g::1", ":::1", "1:2:3:4:5:6:7::8")
            .forEach { assertNull(IpLiteral.parse(it), it) }
    }
}
