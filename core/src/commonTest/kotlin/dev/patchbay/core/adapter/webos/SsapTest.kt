package dev.patchbay.core.adapter.webos

import dev.patchbay.core.adapter.webos.Ssap.Incoming
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsapTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun registerCarriesKeyOnlyWhenKnownAndRequestsMinimalPermissions() {
        val first = obj(Ssap.register(null))
        assertEquals("register", first["type"].toString().trim('"'))
        assertFalse("client-key" in (first["payload"] as JsonObject))
        val again = Ssap.register("abcdef0123456789")
        assertTrue("\"client-key\":\"abcdef0123456789\"" in again)
        assertFalse("CONTROL_MOUSE_AND_KEYBOARD" in again) // we don't ask for what we don't use
    }

    @Test
    fun requestUrisAreValidated() {
        assertTrue("\"uri\":\"ssap://audio/setVolume\"" in Ssap.setVolume("v1", 12))
        assertFailsWith<IllegalArgumentException> { Ssap.request("x", "audio/setVolume\"injected") }
    }

    @Test
    fun parsesPairingSequence() {
        assertIs<Incoming.Hello>(Ssap.parse("""{"type":"hello","id":"hello","payload":{"protocolVersion":1}}""").getOrNull())
        assertIs<Incoming.PromptShown>(Ssap.parse("""{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""").getOrNull())
        val reg = Ssap.parse("""{"type":"registered","id":"register_0","payload":{"client-key":"0123456789abcdef0123456789abcdef"}}""").getOrNull()
        assertIs<Incoming.Registered>(reg)
        assertEquals("0123456789abcdef0123456789abcdef", reg.clientKey)
        assertFalse("0123456789abcdef" in reg.toString())
    }

    @Test
    fun rejectsMalformed() {
        listOf("", "[]", "{}", """{"type":1}""", """{"type":"registered","payload":{}}""",
            """{"type":"registered","payload":{"client-key":"bad key with spaces"}}""", """{"type":"response","payload":[]}""", """{"type":"weird"}""")
            .forEach { assertIs<PatchbayError.ProtocolViolation>(Ssap.parse(it).errorOrNull(), it) }
    }

    @Test
    fun volumeShapesOldAndNew() {
        assertEquals(Ssap.VolumeStatus(14, false), Ssap.volume(obj("""{"volume":14,"muted":false,"returnValue":true}""")))
        assertEquals(Ssap.VolumeStatus(9, true), Ssap.volume(obj("""{"volumeStatus":{"volume":9,"muteStatus":true}}""")))
        assertNull(Ssap.volume(obj("""{"volume":"14"}""")))
        assertNull(Ssap.volume(obj("""{"volume":400}""")))
    }

    @Test
    fun powerAndInputs() {
        assertEquals(true, Ssap.powerOn(obj("""{"state":"Active"}""")))
        assertEquals(false, Ssap.powerOn(obj("""{"state":"Active Standby"}""")))
        assertEquals(listOf("HDMI_1" to "HDMI 1", "HDMI_2" to "PS5"), Ssap.inputs(obj("""{"devices":[{"id":"HDMI_1","label":"HDMI 1"},{"id":"HDMI_2","label":"PS5"},{"nope":1}]}""")))
    }

    @Test
    fun neverThrowsOnMutatedInput() {
        val seed = """{"type":"response","id":"v1","payload":{"volumeStatus":{"volume":9,"muteStatus":true},"devices":[{"id":"HDMI_1"}]}}"""
        val rnd = Random(0x5A9)
        repeat(20_000) {
            val c = seed.toCharArray()
            repeat(rnd.nextInt(1, 6)) { c[rnd.nextInt(c.size)] = (32 + rnd.nextInt(95)).toChar() }
            Ssap.parse(c.concatToString()).getOrNull()?.let { m -> if (m is Incoming.Response) { Ssap.volume(m.payload); Ssap.inputs(m.payload); Ssap.powerOn(m.payload) } }
        }
    }
}
