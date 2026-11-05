package dev.patchbay.core.discovery

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult

/**
 * Minimal, strict DNS message codec for mDNS / DNS-SD discovery (RFC 1035, 6762, 6763).
 *
 * Parsing treats every byte as hostile:
 * - bounded total size (9 000 bytes, the mDNS maximum)
 * - bounded record counts
 * - name compression pointers must point backwards, depth- and length-limited (no loops)
 * - every length field is checked against the remaining buffer
 * Only the record types discovery needs are decoded (PTR, SRV, TXT, A, AAAA); others are skipped.
 */
public object Dns {

    public const val MDNS_PORT: Int = 5353
    public const val MDNS_GROUP_V4: String = "224.0.0.251"
    private const val MAX_MESSAGE = 9_000
    private const val MAX_RECORDS = 200
    private const val MAX_NAME = 255
    private const val MAX_POINTER_HOPS = 16

    public const val TYPE_A: Int = 1
    public const val TYPE_PTR: Int = 12
    public const val TYPE_TXT: Int = 16
    public const val TYPE_AAAA: Int = 28
    public const val TYPE_SRV: Int = 33
    public const val TYPE_ANY: Int = 255

    public sealed interface Record {
        public val name: String
        public val ttl: Long

        public data class Ptr(override val name: String, override val ttl: Long, val target: String) : Record
        public data class Srv(override val name: String, override val ttl: Long, val priority: Int, val weight: Int, val port: Int, val target: String) : Record
        public data class Txt(override val name: String, override val ttl: Long, val entries: Map<String, String>) : Record
        public data class A(override val name: String, override val ttl: Long, val address: ByteArray) : Record {
            override fun equals(other: Any?): Boolean = other is A && other.name == name && other.address.contentEquals(address)
            override fun hashCode(): Int = name.hashCode()
        }
        public data class Aaaa(override val name: String, override val ttl: Long, val address: ByteArray) : Record {
            override fun equals(other: Any?): Boolean = other is Aaaa && other.name == name && other.address.contentEquals(address)
            override fun hashCode(): Int = name.hashCode()
        }
    }

    public data class Message(val id: Int, val isResponse: Boolean, val questions: List<String>, val records: List<Record>)

    /**
     * Builds a query for PTR records of [serviceTypes] (e.g. "_googlecast._tcp.local"). Sent from an
     * ephemeral port, it is a "legacy unicast" query: responders answer by unicast to our port (RFC 6762 §6.7).
     */
    public fun ptrQuery(id: Int, serviceTypes: List<String>): ByteArray {
        require(serviceTypes.size in 1..64)
        val out = ByteWriter()
        out.u16(id); out.u16(0); out.u16(serviceTypes.size); out.u16(0); out.u16(0); out.u16(0)
        for (t in serviceTypes) {
            writeName(out, t)
            out.u16(TYPE_PTR); out.u16(1) // class IN
        }
        return out.bytes()
    }

    public fun parse(bytes: ByteArray): PatchbayResult<Message> {
        if (bytes.size < 12) return bad("message shorter than header")
        if (bytes.size > MAX_MESSAGE) return bad("message too large")
        val r = Reader(bytes)
        return try {
            val id = r.u16()
            val flags = r.u16()
            val qd = r.u16(); val an = r.u16(); val ns = r.u16(); val ar = r.u16()
            if (qd + an + ns + ar > MAX_RECORDS) return bad("too many records")
            val questions = ArrayList<String>(qd)
            repeat(qd) {
                questions += r.name()
                r.skip(4)
            }
            val records = ArrayList<Record>()
            repeat(an + ns + ar) { r.record()?.let(records::add) }
            PatchbayResult.Ok(Message(id, flags and 0x8000 != 0, questions, records))
        } catch (e: DnsFormat) {
            bad(e.message ?: "malformed")
        }
    }

    private class DnsFormat(message: String) : Exception(message)

    private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("dns: $why"))

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun need(n: Int) {
            if (n < 0 || pos + n > b.size) throw DnsFormat("truncated")
        }

        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun skip(n: Int) { need(n); pos += n }

        fun name(): String {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var hops = 0
            var total = 0
            while (true) {
                if (p >= b.size) throw DnsFormat("name runs past end")
                val len = b[p].toInt() and 0xFF
                when {
                    len == 0 -> {
                        if (!jumped) pos = p + 1
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= b.size) throw DnsFormat("truncated pointer")
                        val target = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (target >= p) throw DnsFormat("forward or self pointer")
                        if (++hops > MAX_POINTER_HOPS) throw DnsFormat("too many pointers")
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = target
                    }
                    len and 0xC0 != 0 -> throw DnsFormat("reserved label type")
                    else -> {
                        if (p + 1 + len > b.size) throw DnsFormat("label past end")
                        total += len + 1
                        if (total > MAX_NAME) throw DnsFormat("name too long")
                        labels += b.decodeToString(p + 1, p + 1 + len).replace('.', '․')
                        p += 1 + len
                    }
                }
            }
            return labels.joinToString(".")
        }

        fun record(): Record? {
            val name = name()
            val type = u16()
            u16() // class (top bit = cache-flush in mDNS)
            val ttl = u32()
            val rdlen = u16()
            need(rdlen)
            val start = pos
            val end = pos + rdlen
            val rec: Record? = when (type) {
                TYPE_PTR -> Record.Ptr(name, ttl, name())
                TYPE_SRV -> { if (rdlen < 7) throw DnsFormat("short SRV"); Record.Srv(name, ttl, u16(), u16(), u16(), name()) }
                TYPE_TXT -> Record.Txt(name, ttl, txt(end))
                TYPE_A -> { if (rdlen != 4) throw DnsFormat("bad A length"); Record.A(name, ttl, b.copyOfRange(pos, pos + 4)) }
                TYPE_AAAA -> { if (rdlen != 16) throw DnsFormat("bad AAAA length"); Record.Aaaa(name, ttl, b.copyOfRange(pos, pos + 16)) }
                else -> null
            }
            if (pos > end) throw DnsFormat("record data overran its length")
            pos = start + rdlen
            return rec
        }

        fun txt(end: Int): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            while (pos < end) {
                val len = u8()
                if (pos + len > end) throw DnsFormat("TXT string past rdata")
                val s = b.decodeToString(pos, pos + len)
                pos += len
                if (s.isEmpty() || m.size >= 64) continue
                val eq = s.indexOf('=')
                if (eq > 0) m[s.substring(0, eq).lowercase().take(32)] = s.substring(eq + 1).take(255)
                else if (eq < 0) m[s.lowercase().take(32)] = ""
            }
            return m
        }
    }

    private class ByteWriter {
        private val buf = ArrayList<Byte>()
        fun u8(v: Int) { buf += v.toByte() }
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun bytes(): ByteArray = buf.toByteArray()
        fun raw(b: ByteArray) { b.forEach { buf += it } }
    }

    private fun writeName(out: ByteWriter, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63) { "bad label" }
            out.u8(bytes.size); out.raw(bytes)
        }
        out.u8(0)
    }
}
