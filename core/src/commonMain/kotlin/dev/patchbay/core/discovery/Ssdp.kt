package dev.patchbay.core.discovery

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult

/** SSDP (UPnP discovery) M-SEARCH request and strict response/NOTIFY parsing. */
public object Ssdp {
    public const val GROUP_V4: String = "239.255.255.250"
    public const val PORT: Int = 1900
    private const val MAX_BYTES = 4_096
    private const val MAX_HEADERS = 40

    /** An M-SEARCH for everything. MX=2 asks devices to spread replies over 2 s (never hammer). */
    public fun search(st: String = "ssdp:all", mx: Int = 2): ByteArray {
        require(mx in 1..5)
        require(st.all { it.code in 0x21..0x7E }) { "invalid ST" }
        return (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $GROUP_V4:$PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "\r\n"
            ).encodeToByteArray()
    }

    public data class Reply(
        val kind: Kind,
        /** Header names lowercased; values trimmed and length-capped. */
        val headers: Map<String, String>,
    ) {
        public enum class Kind { SEARCH_RESPONSE, NOTIFY }

        val server: String? get() = headers["server"]
        val usn: String? get() = headers["usn"]
        val st: String? get() = headers["st"] ?: headers["nt"]

        /** The device description URL. Untrusted: it's never fetched unless the host is the reply's LAN source. */
        val location: String? get() = headers["location"]
    }

    public fun parse(bytes: ByteArray): PatchbayResult<Reply> {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return bad("size")
        val text = bytes.decodeToString(throwOnInvalidSequence = false)
        if ('\u0000' in text || '�' in text) return bad("binary content")
        val lines = text.split("\r\n", "\n")
        val first = lines.first().trim()
        val kind = when {
            first.startsWith("HTTP/1.1 200", ignoreCase = true) || first.startsWith("HTTP/1.0 200", ignoreCase = true) -> Reply.Kind.SEARCH_RESPONSE
            first.startsWith("NOTIFY * HTTP/1.1", ignoreCase = true) -> Reply.Kind.NOTIFY
            else -> return bad("not an SSDP reply")
        }
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            if (headers.size >= MAX_HEADERS) return bad("too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) return bad("malformed header")
            val key = line.substring(0, colon).trim().lowercase()
            if (!key.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return bad("bad header name")
            headers[key] = line.substring(colon + 1).trim().take(256)
        }
        return PatchbayResult.Ok(Reply(kind, headers))
    }

    private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("ssdp: $why"))
}
