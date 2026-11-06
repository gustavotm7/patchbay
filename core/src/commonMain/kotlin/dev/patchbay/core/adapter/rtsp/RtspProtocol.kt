package dev.patchbay.core.adapter.rtsp

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult

/**
 * The slice of RTSP 1.0 (RFC 2326) Patchbay speaks to check that a camera is up and that its credentials
 * work: one `DESCRIBE` with optional authentication, header read, then close. No media is requested.
 * Pure and platform-free; the MD5 digest maths lives in `RtspAuth` (jvmShared).
 *
 * Responses are untrusted: the header block is size-capped, line-limited and strictly shaped.
 */
public object RtspProtocol {

    public const val PORT: Int = 554
    public const val MAX_HEADER_BYTES: Int = 8_192
    private const val MAX_HEADER_LINES = 64

    /** Stream paths are short and plain; anything else (spaces, `..`, CR/LF, `?`) is refused, never encoded. */
    public fun isSafePath(path: String): Boolean =
        path.length in 1..64 && path.all { it.isLetterOrDigit() || it == '/' || it == '_' || it == '-' || it == '.' } &&
            !path.contains("..") && !path.startsWith("/")

    /** Credentials go into a header: no controls, quotes, backslashes or whitespace. */
    public fun isSafeCredential(s: String): Boolean =
        s.length in 1..64 && s.none { it.isISOControl() || it == '"' || it == '\\' || it.isWhitespace() }

    public fun uri(host: String, path: String): String = "rtsp://$host:$PORT/$path"

    public fun describe(uri: String, cseq: Int, authorization: String? = null): ByteArray {
        require(uri.none { it == '\r' || it == '\n' || it == ' ' }) { "bad uri" }
        require(authorization == null || authorization.none { it == '\r' || it == '\n' }) { "bad authorization" }
        val sb = StringBuilder()
        sb.append("DESCRIBE ").append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        sb.append("Accept: application/sdp\r\n")
        sb.append("User-Agent: Patchbay\r\n")
        if (authorization != null) sb.append("Authorization: ").append(authorization).append("\r\n")
        sb.append("\r\n")
        return sb.toString().encodeToByteArray()
    }

    public data class Response(val status: Int, val headers: Map<String, List<String>>) {
        public fun header(name: String): List<String> = headers[name.lowercase()].orEmpty()
    }

    /** Parses the status line and headers (everything up to the blank line). */
    public fun parseResponse(bytes: ByteArray): PatchbayResult<Response> {
        if (bytes.isEmpty() || bytes.size > MAX_HEADER_BYTES) return violation("header block size")
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            return violation("not text")
        }
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) return violation("unterminated headers")
        val lines = text.substring(0, end).split("\r\n")
        if (lines.size > MAX_HEADER_LINES) return violation("too many header lines")
        val m = Regex("^RTSP/1\\.0 (\\d{3})(?: .{0,64})?$").matchEntire(lines.first()) ?: return violation("bad status line")
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return violation("bad header line")
            val name = line.substring(0, colon)
            if (name.any { it.isWhitespace() || it.isISOControl() }) return violation("bad header name")
            headers.getOrPut(name.lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        return PatchbayResult.Ok(Response(m.groupValues[1].toInt(), headers))
    }

    public sealed interface Challenge {
        public data class Basic(val realm: String) : Challenge
        public data class Digest(val realm: String, val nonce: String, val qopAuth: Boolean, val opaque: String?) : Challenge
    }

    /** Picks the strongest challenge offered: Digest (MD5 only) over Basic. Returns null if none is usable. */
    public fun parseChallenge(wwwAuthenticate: List<String>): Challenge? {
        var basic: Challenge.Basic? = null
        for (value in wwwAuthenticate) {
            val scheme = value.substringBefore(' ').lowercase()
            val params = parseParams(value.substringAfter(' ', ""))
            when (scheme) {
                "digest" -> {
                    val alg = params["algorithm"]?.uppercase()
                    if (alg != null && alg != "MD5") continue
                    val realm = params["realm"] ?: continue
                    val nonce = params["nonce"]?.takeIf { it.length in 1..128 } ?: continue
                    val qop = params["qop"]?.split(',')?.map { it.trim().lowercase() }.orEmpty()
                    return Challenge.Digest(realm.take(128), nonce, "auth" in qop, params["opaque"]?.take(128))
                }
                "basic" -> basic = Challenge.Basic((params["realm"] ?: "").take(128))
            }
        }
        return basic
    }

    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length && out.size < 16) {
            while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().lowercase()
            i = eq + 1
            val value: String
            if (i < s.length && s[i] == '"') {
                val close = s.indexOf('"', i + 1)
                if (close < 0) break
                value = s.substring(i + 1, close)
                i = close + 1
            } else {
                val comma = s.indexOf(',', i).let { if (it < 0) s.length else it }
                value = s.substring(i, comma).trim()
                i = comma
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun violation(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: $why"))
}
