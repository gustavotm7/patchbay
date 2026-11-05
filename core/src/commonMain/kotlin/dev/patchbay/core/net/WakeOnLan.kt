package dev.patchbay.core.net

/** Wake-on-LAN magic packets: 6 × 0xFF followed by the target MAC 16 times (102 bytes). */
public object WakeOnLan {
    public const val PORT: Int = 9
    public const val BROADCAST: String = "255.255.255.255"
    private val MAC = Regex("^([0-9A-Fa-f]{2})([:-][0-9A-Fa-f]{2}){5}$")

    /** Parses "aa:bb:cc:dd:ee:ff" (or with dashes). Rejects multicast and all-zero MACs. */
    public fun parseMac(text: String): ByteArray? {
        if (!MAC.matches(text.trim())) return null
        val b = text.trim().split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        if (b.all { it.toInt() == 0 } || (b[0].toInt() and 0x01) != 0) return null
        return b
    }

    public fun packet(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must be 6 bytes" }
        val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) mac.copyInto(out, 6 + i * 6)
        return out
    }
}
