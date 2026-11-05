package dev.patchbay.core.log

/**
 * Scrubs secrets and identifiers from log text (privacy invariant 7). Applied to **every** log line
 * by the logger. Callers should still avoid logging secrets; this is the safety net.
 *
 * Redacted:
 * - values of secret-looking keys (`code`, `password`, `token`, `client-key`, `psk`, `secret`, …), in
 *   `key=value`, `key: value` and JSON `"key":"value"` forms
 * - long hex/base64 runs (keys, fingerprints, tokens) → first 4 chars + `…`
 * - MAC addresses and Govee-style 8-byte device IDs → last 2 bytes kept
 * - Bambu-style serials (15 alphanumerics with digits) → last 4 kept
 */
public object Redactor {

    private const val MASK = "•••"

    private val secretKeys = listOf(
        "access_?code", "accesscode", "code", "pin", "password", "passwd", "pass", "token", "client[-_]?key",
        "secret", "psk", "key", "authorization", "auth", "setup_?code",
    ).joinToString("|")

    // "key":"value"  /  "key": 1234
    private val jsonPair = Regex("(\"(?i:$secretKeys)\"\\s*:\\s*)(\"[^\"]*\"|[0-9A-Za-z+/=_-]+)")
    // key=value / key: value (value up to whitespace, comma, semicolon, quote, brace or ampersand)
    private val kvPair = Regex("(?i)\\b($secretKeys)(\\s*[=:]\\s*)([^\\s,;\"'}&]+)")
    private val mac = Regex("\\b([0-9A-Fa-f]{2}[:-]){5,7}([0-9A-Fa-f]{2})\\b")
    private val longToken = Regex("\\b[0-9A-Za-z+/_-]{24,}={0,2}")
    private val hexRun = Regex("\\b[0-9a-fA-F]{16,}\\b")
    private val serial = Regex("\\b(?=[0-9A-Z]*[0-9])(?=[0-9A-Z]*[A-Z])[0-9A-Z]{11}([0-9A-Z]{4})\\b")

    public fun redact(text: String): String {
        var s = text
        s = jsonPair.replace(s) { m -> m.groupValues[1] + "\"$MASK\"" }
        s = kvPair.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = mac.replace(s) { m ->
            val parts = m.value.split(':', '-')
            (List(parts.size - 2) { "**" } + parts.takeLast(2)).joinToString(":")
        }
        s = serial.replace(s) { m -> "…" + m.groupValues[1] }
        s = longToken.replace(s) { m -> m.value.take(4) + "…" }
        s = hexRun.replace(s) { m -> m.value.take(4) + "…" }
        return s
    }
}
