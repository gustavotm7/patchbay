package dev.patchbay.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    private fun r(s: String) = Redactor.redact(s)

    @Test
    fun masksSecretKeyValues() {
        assertFalse("12345678" in r("bambu access_code=12345678 ok"))
        assertFalse("hunter2" in r("password: hunter2"))
        assertFalse("abc123" in r("""{"client-key":"abc123","x":1}"""))
        assertFalse("8a7f" in r("""{"psk": "8a7f"}"""))
        assertTrue("x\":1" in r("""{"client-key":"abc123","x":1}"""))
    }

    @Test
    fun masksMacsAndDeviceIdsKeepingTail() {
        assertEquals("light **:**:**:**:**:**:72:4E on", r("light 1F:80:C5:32:32:36:72:4E on"))
        assertEquals("gw **:**:**:**:ee:ff", r("gw aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun masksSerialsKeysAndFingerprints() {
        val out = r("printer 01P00A123456789 fp 3f9a8c2e1b7d4f60a1b2c3d4e5f60718 tok eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertFalse("01P00A123456789" in out)
        assertTrue("…6789" in out)
        assertFalse("3f9a8c2e1b7d4f60a1b2c3d4e5f60718" in out)
        assertFalse("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" in out)
    }

    @Test
    fun masksTuyaDeviceIdsKeepingTheTail() {
        assertEquals("tuya …fasi online", r("tuya ebf5889331f7b82250fasi online"))
        assertFalse("bf1234567890abcdef12" in r("device bf1234567890abcdef12 answered"))
        assertEquals("device …a9z1", r("device AbCdEfGh1234567890a9z1"))
    }

    @Test
    fun leavesLongWordsAndClassNamesAlone() {
        val s = "ColourTemperatureState and kotlinx.coroutines.DispatchedTask at 2026-11-17T20:41:03.123+01:00 in Dispatchers.IO"
        assertEquals(s, r(s))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val s = "Govee H6076 at 192.168.1.23 brightness 40 % colour 2700 K, layer 112/240"
        assertEquals(s, r(s))
    }
}
