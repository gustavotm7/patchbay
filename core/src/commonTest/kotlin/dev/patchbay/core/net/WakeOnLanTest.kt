package dev.patchbay.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeOnLanTest {
    @Test
    fun magicPacketLayout() {
        val mac = assertNotNull(WakeOnLan.parseMac("a8:23:fe:12:34:56"))
        val p = WakeOnLan.packet(mac)
        assertEquals(102, p.size)
        assertTrue((0 until 6).all { p[it] == 0xFF.toByte() })
        assertTrue((0 until 16).all { i -> p.copyOfRange(6 + i * 6, 12 + i * 6).contentEquals(mac) })
    }

    @Test
    fun rejectsBadMacs() {
        listOf("", "a8:23:fe:12:34", "zz:23:fe:12:34:56", "00:00:00:00:00:00", "01:00:5e:00:00:fb", "a8:23:fe:12:34:56:78")
            .forEach { assertNull(WakeOnLan.parseMac(it), it) }
        assertNotNull(WakeOnLan.parseMac("A8-23-FE-12-34-56"))
    }
}
