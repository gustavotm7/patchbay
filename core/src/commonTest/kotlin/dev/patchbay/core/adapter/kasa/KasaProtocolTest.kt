package dev.patchbay.core.adapter.kasa

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KasaProtocolTest {

    private val device40 = "8006" + "0".repeat(36)

    private fun sysinfo(extra: String = "", relay: String = "1", type: String = "IOT.SMARTPLUGSWITCH", id: String = device40) =
        """{"system":{"get_sysinfo":{"err_code":0,"sw_ver":"1.5.6","model":"HS103(US)","type":"$type","alias":"Desk lamp","relay_state":$relay,"deviceId":"$id"$extra}}}"""
            .encodeToByteArray()

    @Test
    fun scrambleMatchesPublishedPrefixAndRoundTrips() {
        // First bytes of the well-known get_sysinfo query: 171^'{'=0xd0, 0xd0^'"'=0xf2, 0xf2^'s'=0x81.
        val wire = KasaProtocol.scramble(KasaProtocol.GET_SYSINFO.encodeToByteArray())
        assertEquals(listOf(0xd0, 0xf2, 0x81), wire.take(3).map { it.toInt() and 0xFF })
        assertContentEquals(KasaProtocol.GET_SYSINFO.encodeToByteArray(), KasaProtocol.unscramble(wire))
        assertContentEquals(wire, KasaProtocol.discoveryDatagram)
    }

    @Test
    fun framePrefixesBigEndianLength() {
        val f = KasaProtocol.frame(KasaProtocol.setRelay(true))
        val len = KasaProtocol.frameLength(f.copyOf(4))
        assertIs<PatchbayResult.Ok<Int>>(len)
        assertEquals(f.size - 4, len.value)
        assertEquals("""{"system":{"set_relay_state":{"state":1}}}""", KasaProtocol.unscramble(f.copyOfRange(4, f.size)).decodeToString())
    }

    @Test
    fun frameLengthRejectsBadHeaders() {
        assertIs<PatchbayResult.Err>(KasaProtocol.frameLength(byteArrayOf(0, 0, 0)))
        assertIs<PatchbayResult.Err>(KasaProtocol.frameLength(byteArrayOf(0, 0, 0, 0)))
        assertIs<PatchbayResult.Err>(KasaProtocol.frameLength(byteArrayOf(0x7f, 0, 0, 0)))
        assertIs<PatchbayResult.Err>(KasaProtocol.frameLength(byteArrayOf(0, 0, 0x10, 1))) // 4097
    }

    @Test
    fun parsesAPlug() {
        val r = KasaProtocol.parseSysinfo(sysinfo())
        assertIs<PatchbayResult.Ok<KasaProtocol.Sysinfo>>(r)
        assertEquals(KasaProtocol.Sysinfo(device40, "Desk lamp", "HS103(US)", "1.5.6", true), r.value)
        assertEquals(false, (KasaProtocol.parseSysinfo(sysinfo(relay = "0")) as PatchbayResult.Ok).value.relayOn)
    }

    @Test
    fun fallsBackToMacWhenDeviceIdIsMissingOrMalformed() {
        val r = KasaProtocol.parseSysinfo(sysinfo(id = "short", extra = ""","mac":"AA:BB:CC:00:11:22""""))
        assertEquals("aabbcc001122", (r as PatchbayResult.Ok).value.deviceKey)
    }

    @Test
    fun rejectsOutOfSpecValues() {
        listOf(
            sysinfo(relay = "2"),
            sysinfo(relay = "\"1\""),
            sysinfo(relay = "true"),
            sysinfo(relay = "1.5"),
            sysinfo(id = "zz".repeat(20)),
            "{}".encodeToByteArray(),
            "[]".encodeToByteArray(),
            "not json".encodeToByteArray(),
            ByteArray(KasaProtocol.MAX_MESSAGE_BYTES + 1) { 'a'.code.toByte() },
            sysinfo(extra = ""","alias2":"x"""").copyOf(5),
        ).forEach { assertIs<PatchbayResult.Err>(KasaProtocol.parseSysinfo(it)) }
    }

    @Test
    fun nonPlugsAndStripsAreUnsupportedNotViolations() {
        val bulb = KasaProtocol.parseSysinfo(sysinfo(type = "IOT.SMARTBULB"))
        assertIs<PatchbayError.Unsupported>((bulb as PatchbayResult.Err).error)
        val strip = KasaProtocol.parseSysinfo(sysinfo(extra = ""","children":[{"id":"00","state":1}]"""))
        assertIs<PatchbayError.Unsupported>((strip as PatchbayResult.Err).error)
    }

    @Test
    fun deviceErrorCodeIsRejected() {
        val bad = """{"system":{"get_sysinfo":{"err_code":-1}}}""".encodeToByteArray()
        assertIs<PatchbayResult.Err>(KasaProtocol.parseSysinfo(bad))
    }

    @Test
    fun parsesSetRelayReplies() {
        assertIs<PatchbayResult.Ok<Unit>>(KasaProtocol.parseSetRelay("""{"system":{"set_relay_state":{"err_code":0}}}""".encodeToByteArray()))
        assertIs<PatchbayResult.Err>(KasaProtocol.parseSetRelay("""{"system":{"set_relay_state":{"err_code":-3}}}""".encodeToByteArray()))
        assertIs<PatchbayResult.Err>(KasaProtocol.parseSetRelay("""{"system":{"set_relay_state":{}}}""".encodeToByteArray()))
    }

    @Test
    fun mutatedInputNeverThrows() {
        val rnd = Random(0x4B415341)
        val seeds = listOf(sysinfo(), sysinfo(extra = ""","children":[]"""), """{"system":{"set_relay_state":{"err_code":0}}}""".encodeToByteArray())
        repeat(25_000) {
            val bytes = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(4)) { _ -> bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) bytes.copyOf(rnd.nextInt(bytes.size + 1)) else bytes
            KasaProtocol.parseSysinfo(cut)
            KasaProtocol.parseSetRelay(cut)
        }
        assertTrue(true)
    }
}
