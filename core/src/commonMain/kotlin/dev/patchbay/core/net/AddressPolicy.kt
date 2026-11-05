package dev.patchbay.core.net

/**
 * The destination policy enforced by NetGuard (privacy invariant 1). Pure and platform-free so
 * it can be exhaustively unit tested. NetGuard's socket factories (Phase 1) resolve a
 * destination to raw address bytes and call [check] **after** DNS resolution and before connecting,
 * so a hostname can never smuggle a public address past the policy.
 *
 * Allowed: RFC 1918, IPv4 link-local, loopback, IPv6 link-local, IPv6 ULA, and a fixed list of
 * discovery multicast/broadcast groups. Everything else, including CGNAT (100.64/10), IPv6 global
 * unicast, NAT64 and 6to4, is refused.
 */
public object AddressPolicy {

    public enum class Verdict(public val allowed: Boolean) {
        PRIVATE_V4(true),
        LINK_LOCAL_V4(true),
        LOOPBACK(true),
        LINK_LOCAL_V6(true),
        UNIQUE_LOCAL_V6(true),
        DISCOVERY_MULTICAST(true),
        LIMITED_BROADCAST(true),
        DENIED_PUBLIC(false),
        DENIED_MULTICAST(false),
        DENIED_SPECIAL(false),
        DENIED_MALFORMED(false),
    }

    /** Multicast groups needed for discovery. Adding one requires an ADR. */
    public val discoveryGroupsV4: Set<String> = setOf(
        "224.0.0.251", // mDNS / DNS-SD
        "239.255.255.250", // SSDP (LG webOS, Bambu), Govee LAN scan
    )
    public val discoveryGroupsV6: Set<String> = setOf(
        "ff02::fb", // mDNS link-local
        "ff02::c", // SSDP link-local
    )

    private val groupBytesV4: List<ByteArray> = discoveryGroupsV4.map { requireNotNull(IpLiteral.parse(it)) }
    private val groupBytesV6: List<ByteArray> = discoveryGroupsV6.map { requireNotNull(IpLiteral.parse(it)) }

    public fun check(address: ByteArray): Verdict = when (address.size) {
        4 -> checkV4(address)
        16 -> checkV6(address)
        else -> Verdict.DENIED_MALFORMED
    }

    public fun check(literal: String): Verdict =
        IpLiteral.parse(literal)?.let(::check) ?: Verdict.DENIED_MALFORMED

    public fun isAllowed(address: ByteArray): Boolean = check(address).allowed

    private fun checkV4(a: ByteArray): Verdict {
        val b0 = a[0].u()
        val b1 = a[1].u()
        return when {
            b0 == 10 -> Verdict.PRIVATE_V4
            b0 == 172 && b1 in 16..31 -> Verdict.PRIVATE_V4
            b0 == 192 && b1 == 168 -> Verdict.PRIVATE_V4
            b0 == 169 && b1 == 254 -> Verdict.LINK_LOCAL_V4
            b0 == 127 -> Verdict.LOOPBACK
            a.all { it.u() == 255 } -> Verdict.LIMITED_BROADCAST
            b0 in 224..239 -> if (groupBytesV4.any { it.contentEquals(a) }) Verdict.DISCOVERY_MULTICAST else Verdict.DENIED_MULTICAST
            b0 == 0 || b0 >= 240 -> Verdict.DENIED_SPECIAL
            else -> Verdict.DENIED_PUBLIC
        }
    }

    private fun checkV6(a: ByteArray): Verdict {
        // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 address, never the wrapper.
        if ((0 until 10).all { a[it].u() == 0 } && a[10].u() == 0xFF && a[11].u() == 0xFF) {
            return checkV4(a.copyOfRange(12, 16))
        }
        val b0 = a[0].u()
        val b1 = a[1].u()
        return when {
            (0 until 15).all { a[it].u() == 0 } && a[15].u() == 1 -> Verdict.LOOPBACK
            a.all { it.u() == 0 } -> Verdict.DENIED_SPECIAL // unspecified ::
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> Verdict.LINK_LOCAL_V6 // fe80::/10
            (b0 and 0xFE) == 0xFC -> Verdict.UNIQUE_LOCAL_V6 // fc00::/7
            b0 == 0xFF -> if (groupBytesV6.any { it.contentEquals(a) }) Verdict.DISCOVERY_MULTICAST else Verdict.DENIED_MULTICAST
            // Everything else — global unicast, NAT64 (64:ff9b::/96), 6to4 (2002::/16), Teredo,
            // IPv4-compatible (::a.b.c.d), deprecated site-local (fec0::/10) — is refused.
            b0 == 0 -> Verdict.DENIED_SPECIAL
            else -> Verdict.DENIED_PUBLIC
        }
    }

    private fun Byte.u(): Int = toInt() and 0xFF
}

/**
 * A strict IP literal parser for user input and config. It rejects anything ambiguous: leading zeros
 * in IPv4 octets (which some stacks read as octal), zone IDs, brackets, and over-long groups.
 */
public object IpLiteral {

    public fun parse(text: String): ByteArray? {
        if (text.isEmpty() || text.length > 45) return null
        return if (':' in text) parseV6(text) else parseV4(text)
    }

    public fun parseV4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    public fun parseV6(text: String): ByteArray? {
        if (text.count { it == ':' } < 2) return null
        val doubleColon = text.indexOf("::")
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null

        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = text.substring(0, doubleColon).let { if (it.isEmpty()) emptyList() else it.split(':') }
            tail = text.substring(doubleColon + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
        } else {
            head = text.split(':')
            tail = emptyList()
        }

        val words = ArrayList<Int>(8)
        fun parseGroups(groups: List<String>, allowV4AtEnd: Boolean, into: MutableList<Int>): Boolean {
            for ((i, g) in groups.withIndex()) {
                if (allowV4AtEnd && i == groups.lastIndex && '.' in g) {
                    val v4 = parseV4(g) ?: return false
                    into += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                    into += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                    continue
                }
                if (g.isEmpty() || g.length > 4) return false
                if (!g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
                into += g.toInt(16)
            }
            return true
        }

        val headWords = ArrayList<Int>()
        val tailWords = ArrayList<Int>()
        if (!parseGroups(head, allowV4AtEnd = doubleColon < 0, into = headWords)) return null
        if (!parseGroups(tail, allowV4AtEnd = true, into = tailWords)) return null

        val total = headWords.size + tailWords.size
        if (doubleColon >= 0) {
            if (total > 7) return null
            words += headWords
            repeat(8 - total) { words += 0 }
            words += tailWords
        } else {
            if (total != 8) return null
            words += headWords
        }

        val out = ByteArray(16)
        for ((i, w) in words.withIndex()) {
            out[2 * i] = (w shr 8).toByte()
            out[2 * i + 1] = w.toByte()
        }
        return out
    }
}
