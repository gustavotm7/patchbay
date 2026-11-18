package dev.patchbay.core.log

/**
 * Scrubs secrets and identifiers from log text (privacy invariant 7). Applied to **every** log line
 * by the logger. Callers should still avoid logging secrets; this is the safety net.
 *
 * Redacted:
 * - values of secret-looking keys (`code`, `password`, `token`, `client-key`, `psk`, `secret`, …), in
 *   `key=value`, `key: value` and JSON `"key":"value"` forms
 * - the password in a URL (`rtsp://user:password@host`), the credential after `Basic` or `Bearer`, a code or password written after a space ("access code 12345678"),
 *   and a HomeKit setup code (`123-45-678`)
 * - long hex/base64 runs (keys, fingerprints, tokens) → first 4 chars + `…`
 * - MAC addresses and Govee-style 8-byte device IDs → last 2 bytes kept
 * - Bambu-style serials (15 alphanumerics with digits) → last 4 kept
 * - Tuya-style device IDs (20 to 23 letters and digits, mixed) → last 4 kept
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
    // 20 to 23 letters and digits with at least one of each: a Tuya device ID. (24 and longer are caught by longToken; words have no digits.)
    private val idToken = Regex("\\b(?=[0-9A-Za-z]*[0-9])(?=[0-9A-Za-z]*[A-Za-z])[0-9A-Za-z]{20,23}\\b")
    private val serial = Regex("\\b(?=[0-9A-Z]*[0-9])(?=[0-9A-Z]*[A-Z])[0-9A-Z]{11}([0-9A-Z]{4})\\b")

    // rtsp://user:password@host  ->  rtsp://•••@host
    private val urlUserinfo = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@]+@")
    // Authorization: Basic YWRtaW46...  /  Bearer abc...  (any length; a safety net, so it may also hide the word after "basic")
    private val scheme = Regex("(?i)\\b(basic|bearer)\\s+[A-Za-z0-9+/=._~-]{8,}")
    // "access code 12345678", "password hunter2", "pin 4821": a space instead of = or :, and the value has a digit
    private val spoken = Regex("(?i)\\b(access\\s*code|setup\\s*code|pairing\\s*code|passcode|password|pin)\\s+(?:is\\s+)?(?=[^\\s]*[0-9])[^\\s,;\"'}&]+")
    // HomeKit setup code
    private val setupCode = Regex("\\b[0-9]{3}-[0-9]{2}-[0-9]{3}\\b")

    public fun redact(text: String): String {
        var s = text
        s = urlUserinfo.replace(s) { m -> m.groupValues[1] + "$MASK@" }
        s = scheme.replace(s) { m -> m.groupValues[1] + " $MASK" }
        s = spoken.replace(s) { m -> m.groupValues[1] + " $MASK" }
        s = setupCode.replace(s, MASK)
        s = jsonPair.replace(s) { m -> m.groupValues[1] + "\"$MASK\"" }
        s = kvPair.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = mac.replace(s) { m ->
            val parts = m.value.split(':', '-')
            (List(parts.size - 2) { "**" } + parts.takeLast(2)).joinToString(":")
        }
        s = serial.replace(s) { m -> "…" + m.groupValues[1] }
        s = longToken.replace(s) { m -> m.value.take(4) + "…" }
        s = hexRun.replace(s) { m -> m.value.take(4) + "…" }
        s = idToken.replace(s) { m -> "…" + m.value.takeLast(4) }
        return s
    }
}
