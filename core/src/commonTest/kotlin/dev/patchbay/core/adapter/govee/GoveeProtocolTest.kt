package dev.patchbay.core.adapter.govee

import dev.patchbay.core.adapter.govee.GoveeProtocol.Message
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoveeProtocolTest {

    private fun parse(s: String) = GoveeProtocol.parse(s.encodeToByteArray())
    private fun rejects(s: String) = assertIs<PatchbayError.ProtocolViolation>(parse(s).errorOrNull(), s)

    @Test
    fun encodesCommandsExactly() {
        assertEquals("""{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}""", GoveeProtocol.scan.decodeToString())
        assertEquals("""{"msg":{"cmd":"devStatus","data":{}}}""", GoveeProtocol.devStatus.decodeToString())
        assertEquals("""{"msg":{"cmd":"turn","data":{"value":1}}}""", GoveeProtocol.turn(true).decodeToString())
        assertEquals("""{"msg":{"cmd":"brightness","data":{"value":40}}}""", GoveeProtocol.brightness(40).decodeToString())
        assertEquals(
            """{"msg":{"cmd":"colorwc","data":{"color":{"r":46,"g":196,"b":182},"colorTemInKelvin":0}}}""",
            GoveeProtocol.colour(Rgb(46, 196, 182)).decodeToString(),
        )
        assertEquals(
            """{"msg":{"cmd":"colorwc","data":{"color":{"r":0,"g":0,"b":0},"colorTemInKelvin":2700}}}""",
            GoveeProtocol.colourTemperature(Kelvin(2700)).decodeToString(),
        )
    }

    @Test
    fun parsesScanReply() {
        val m = parse(
            """{"msg":{"cmd":"scan","data":{"ip":"192.168.1.23","device":"1F:80:C5:32:32:36:72:4E","sku":"H6076",
               "bleVersionHard":"3.01.01","bleVersionSoft":"1.03.01","wifiVersionHard":"1.00.10","wifiVersionSoft":"1.02.03"}}}""",
        ).getOrNull()
        assertIs<Message.ScanReply>(m)
        assertEquals("1f80c5323236724e", m.deviceKey)
        assertEquals("H6076", m.sku)
        assertEquals("192.168.1.23", m.claimedIp)
        assertEquals("1.02.03", m.wifiFirmware)
    }

    @Test
    fun parsesStatusInRgbAndCctModes() {
        val rgb = parse("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":40,"color":{"r":46,"g":196,"b":182},"colorTemInKelvin":0}}}""").getOrNull()
        assertIs<Message.Status>(rgb)
        assertEquals(Rgb(46, 196, 182), rgb.colour)
        assertNull(rgb.kelvin)
        assertTrue(rgb.toStates().any { it is CapabilityState.RgbState })

        val cct = parse("""{"msg":{"cmd":"devStatus","data":{"onOff":0,"brightness":100,"color":{"r":0,"g":0,"b":0},"colorTemInKelvin":2700}}}""").getOrNull()
        assertIs<Message.Status>(cct)
        assertEquals(false, cct.on)
        assertEquals(Kelvin(2700), cct.kelvin)
        assertTrue(cct.toStates().any { it is CapabilityState.ColourTemperatureState })
        assertTrue(cct.toStates().none { it is CapabilityState.RgbState })
    }

    @Test
    fun ignoresUnknownExtraFields() {
        val m = parse("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":5,"futureField":[1,2,3]}},"extra":true}""")
        assertIs<PatchbayResult.Ok<Message>>(m)
    }

    @Test
    fun rejectsOutOfSpecValuesInsteadOfClamping() {
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":200}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":2,"brightness":20}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20,"color":{"r":300,"g":0,"b":0}}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20,"colorTemInKelvin":1500}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20,"colorTemInKelvin":-1}}}""")
    }

    @Test
    fun rejectsWrongTypes() {
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":"1","brightness":20}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20.5}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":2e1}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":true,"brightness":20}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20,"color":"red"}}}""")
        rejects("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":20,"colorTemInKelvin":"2700"}}}""")
        rejects("""{"msg":{"cmd":"scan","data":{"device":"1F:80","sku":"H6076"}}}""")
        rejects("""{"msg":{"cmd":"scan","data":{"device":"1F:80:C5:32:32:36:72:4E","sku":"H6076; rm -rf"}}}""")
        rejects("""{"msg":{"cmd":"scan","data":{"device":"1F:80:C5:32:32:36:72:4E","sku":"H6076","ip":{}}}}""")
    }

    @Test
    fun rejectsStructuralGarbage() {
        listOf(
            "", "null", "[]", "{}", """{"msg":[]}""", """{"msg":{"cmd":"turn","data":{}}}""",
            """{"msg":{"cmd":"devStatus"}}""", """{"msg":{"cmd":1,"data":{}}}""", "{\"msg\":", "\u0000\u0001",
        ).forEach { rejects(it) }
    }

    @Test
    fun boundsSizeAndDepth() {
        val deep = "[".repeat(5_000)
        assertIs<PatchbayError.ProtocolViolation>(GoveeProtocol.parse(deep.encodeToByteArray()).errorOrNull())
        val big = """{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":1,"pad":"${"x".repeat(3_000)}"}}}"""
        assertIs<PatchbayError.ProtocolViolation>(parse(big).errorOrNull())
        // Brackets inside strings don't count towards depth.
        assertIs<PatchbayResult.Ok<Message>>(parse("""{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":1,"x":"[[[[[[[[[["}}}"""))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = """{"msg":{"cmd":"scan","data":{"device":"1F:80:C5:32:32:36:72:4E","sku":"H6076"}}}""".encodeToByteArray().copyOf()
        bytes[30] = 0xFF.toByte()
        assertIs<PatchbayError.ProtocolViolation>(GoveeProtocol.parse(bytes).errorOrNull())
    }

    /** Cheap always-on fuzzing: random bytes and mutated valid messages must never throw. (Jazzer runs in CI too.) */
    @Test
    fun neverThrowsOnMutatedInput() {
        val seeds = listOf(
            """{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":40,"color":{"r":46,"g":196,"b":182},"colorTemInKelvin":0}}}""",
            """{"msg":{"cmd":"scan","data":{"ip":"192.168.1.23","device":"1F:80:C5:32:32:36:72:4E","sku":"H6076"}}}""",
        ).map { it.encodeToByteArray() }
        val rnd = Random(0x60FEE)
        repeat(20_000) { i ->
            val input = if (i % 5 == 0) {
                ByteArray(rnd.nextInt(0, 300)) { rnd.nextInt().toByte() }
            } else {
                val s = seeds[i % seeds.size].copyOf()
                repeat(rnd.nextInt(1, 6)) { s[rnd.nextInt(s.size)] = rnd.nextInt(32, 127).toByte() }
                s
            }
            GoveeProtocol.parse(input) // must return, never throw
        }
    }
}
