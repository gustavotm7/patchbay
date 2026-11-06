package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuyaProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun crcMatchesTheStandardCheckValue() {
        val b = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926.toInt(), TuyaProtocol.crc32(b, 0, b.size))
    }

    @Test
    fun encodeMatchesAnIndependentlyBuiltFrame() {
        // Built with Python's zlib.crc32, not with this code.
        val expected = "000055aa000000010000000a0000000d68656c6c6f06ed37d70000aa55"
        assertEquals(expected, hex(TuyaProtocol.encode(1, TuyaProtocol.CMD_DP_QUERY, "hello".encodeToByteArray())))
    }

    @Test
    fun decodeRoundTripsRequestsAndDeviceReplies() {
        val req = TuyaProtocol.decode(TuyaProtocol.encode(7, 0x07, byteArrayOf(1, 2, 3)), fromDevice = false)
        assertIs<PatchbayResult.Ok<TuyaProtocol.Frame>>(req)
        assertEquals(7, req.value.seq); assertEquals(0x07, req.value.cmd); assertNull(req.value.retcode)
        assertContentEquals(byteArrayOf(1, 2, 3), req.value.payload)
        val reply = TuyaProtocol.decode(TuyaProtocol.encode(7, 0x0A, byteArrayOf(0, 0, 0, 0, 9, 9)), fromDevice = true)
        assertIs<PatchbayResult.Ok<TuyaProtocol.Frame>>(reply)
        assertEquals(0, reply.value.retcode)
        assertContentEquals(byteArrayOf(9, 9), reply.value.payload)
    }

    @Test
    fun headerCheckBoundsTheLength() {
        val good = TuyaProtocol.encode(1, 0x0A, ByteArray(10)).copyOf(16)
        assertEquals(18, (TuyaProtocol.remainingAfterHeader(good) as PatchbayResult.Ok).value)
        assertIs<PatchbayResult.Err>(TuyaProtocol.remainingAfterHeader(good.copyOf(15)))
        assertIs<PatchbayResult.Err>(TuyaProtocol.remainingAfterHeader(bytes("deadbeef000000010000000a00000010")))
        assertIs<PatchbayResult.Err>(TuyaProtocol.remainingAfterHeader(bytes("000055aa000000010000000a7fffffff")))
        assertIs<PatchbayResult.Err>(TuyaProtocol.remainingAfterHeader(bytes("000055aa000000010000000a00000004")))
    }

    @Test
    fun decodeRejectsCorruptFrames() {
        val f = TuyaProtocol.encode(1, 0x0A, byteArrayOf(0, 0, 0, 0, 5, 5))
        fun flip(i: Int) = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
        listOf(flip(0), flip(13), flip(17), flip(f.size - 6), flip(f.size - 1), f.copyOf(f.size - 1), f.copyOf(10)).forEach {
            assertIs<PatchbayResult.Err>(TuyaProtocol.decode(it, fromDevice = true))
        }
        assertIs<PatchbayResult.Err>(TuyaProtocol.decode(TuyaProtocol.encode(1, 0x0A, byteArrayOf(1)), fromDevice = true)) // no room for a return code
    }

    @Test
    fun versionHeaderIsStrippedOnlyWhenPresent() {
        val body = "x".repeat(20).encodeToByteArray()
        assertContentEquals(body, TuyaProtocol.stripVersionHeader(TuyaProtocol.VERSION_HEADER + body))
        assertContentEquals(body, TuyaProtocol.stripVersionHeader(body))
    }

    @Test
    fun parseDpsIsStrict() {
        val ok = TuyaProtocol.parseDps("""{"devId":"x","dps":{"20":true,"22":500,"21":"white"}}""".encodeToByteArray())
        assertIs<PatchbayResult.Ok<Map<String, JsonPrimitive>>>(ok)
        assertEquals(3, ok.value.size)
        listOf(
            "{}", "[]", "nope", """{"dps":[]}""", """{"dps":{"abcd":1}}""", """{"dps":{"":1}}""", """{"dps":{"20":{"a":1}}}""",
            """{"dps":{"20":"${"a".repeat(300)}"}}""", "",
        ).forEach { assertIs<PatchbayResult.Err>(TuyaProtocol.parseDps(it.encodeToByteArray()), it) }
    }

    @Test
    fun bulbV2CommandsMapToAllowlistedDataPoints() {
        val p = TuyaBulbProfile.V2
        assertEquals(mapOf("20" to JsonPrimitive(true)), (p.toDps(Command.SetPower(true)) as PatchbayResult.Ok).value)
        assertEquals(JsonPrimitive(10), (p.toDps(Command.SetBrightness(Percent(1.0))) as PatchbayResult.Ok).value["22"])
        assertEquals(JsonPrimitive(1000), (p.toDps(Command.SetBrightness(Percent(100.0))) as PatchbayResult.Ok).value["22"])
        val warm = (p.toDps(Command.SetColourTemperature(Kelvin(2700))) as PatchbayResult.Ok).value
        assertEquals(mapOf("21" to JsonPrimitive("white"), "23" to JsonPrimitive(0)), warm)
        assertEquals(JsonPrimitive(1000), (p.toDps(Command.SetColourTemperature(Kelvin(6500))) as PatchbayResult.Ok).value["23"])
        val red = (p.toDps(Command.SetColour(Rgb(255, 0, 0))) as PatchbayResult.Ok).value
        assertEquals(mapOf("21" to JsonPrimitive("colour"), "24" to JsonPrimitive("000003e803e8")), red)
        assertEquals("00780 3e803e8".replace(" ", ""), (p.toDps(Command.SetColour(Rgb(0, 255, 0))) as PatchbayResult.Ok).value["24"]!!.content)
    }

    @Test
    fun bulbV1UsesTheOlderLayout() {
        val p = TuyaBulbProfile.V1
        assertEquals(mapOf("1" to JsonPrimitive(false)), (p.toDps(Command.SetPower(false)) as PatchbayResult.Ok).value)
        assertEquals(JsonPrimitive(255), (p.toDps(Command.SetBrightness(Percent(100.0))) as PatchbayResult.Ok).value["3"])
        assertEquals("ff000000 00ffff".replace(" ", "").lowercase(), (p.toDps(Command.SetColour(Rgb(255, 0, 0))) as PatchbayResult.Ok).value["5"]!!.content)
    }

    @Test
    fun unsupportedCommandsAreRefused() {
        assertIs<PatchbayResult.Err>(TuyaBulbProfile.V2.toDps(Command.SetVolume(3)))
    }

    @Test
    fun statesRoundTripAndIgnoreUnknownPoints() {
        val p = TuyaBulbProfile.V2
        val white = p.states(mapOf("20" to JsonPrimitive(true), "21" to JsonPrimitive("white"), "22" to JsonPrimitive(1000), "23" to JsonPrimitive(500), "99" to JsonPrimitive("junk")))
        assertTrue(CapabilityState.PowerState(true) in white)
        assertTrue(CapabilityState.BrightnessState(Percent(100.0)) in white)
        assertTrue(CapabilityState.ColourTemperatureState(Kelvin(4600)) in white)
        val colour = p.states(mapOf("21" to JsonPrimitive("colour"), "24" to JsonPrimitive("007803e803e8")))
        assertTrue(CapabilityState.RgbState(Rgb(0, 255, 0)) in colour)
        // Out-of-range and malformed values are dropped, not clamped.
        assertEquals(emptyList(), p.states(mapOf("22" to JsonPrimitive(5), "21" to JsonPrimitive("colour"), "24" to JsonPrimitive("zzzz"))))
    }

    @Test
    fun mutatedInputNeverThrows() {
        val rnd = Random(0x54555941)
        val seeds = listOf(
            TuyaProtocol.encode(3, 0x0A, byteArrayOf(0, 0, 0, 0) + """{"dps":{"20":true}}""".encodeToByteArray()),
            """{"devId":"x","dps":{"20":true,"24":"000003e803e8"}}""".encodeToByteArray(),
        )
        repeat(25_000) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(4)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            TuyaProtocol.decode(cut, true); TuyaProtocol.decode(cut, false)
            if (cut.size >= 16) TuyaProtocol.remainingAfterHeader(cut.copyOf(16))
            TuyaProtocol.parseDps(cut)
            TuyaProtocol.parseDps(TuyaProtocol.stripVersionHeader(cut))
        }
    }
}
