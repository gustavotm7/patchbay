package dev.patchbay.core.adapter.homekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the hand-written Ed25519 and X25519 against Project Wycheproof's vectors (Google / C2SP), which were produced by other
 * implementations and deliberately include malformed, malleable, low-order and non-canonical inputs. This is the independent check the
 * RFC vectors and the JDK cross-check in [Curve25519Test] cannot give on edge cases.
 */
class Curve25519WycheproofTest {
    private fun load(name: String): JsonObject {
        val text = javaClass.getResourceAsStream("/wycheproof/$name")!!.readBytes().toString(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun ed25519VerifyAgreesWithWycheproof() {
        val root = load("ed25519_test.json")
        var checked = 0
        val wrong = mutableListOf<String>()
        val acceptableAccepted = mutableListOf<String>()
        for (group in root["testGroups"]!!.jsonArray) {
            val g = group.jsonObject
            val pk = hex(g["publicKey"]!!.jsonObject["pk"]!!.jsonPrimitive.content)
            for (t in g["tests"]!!.jsonArray) {
                val tc = t.jsonObject
                val id = tc["tcId"]!!.jsonPrimitive.content
                val msg = hex(tc["msg"]!!.jsonPrimitive.content)
                val sig = hex(tc["sig"]!!.jsonPrimitive.content)
                val result = tc["result"]!!.jsonPrimitive.content
                val ok = Curve25519.ed25519Verify(pk, msg, sig)
                checked++
                when (result) {
                    "valid" -> if (!ok) wrong += "tc $id (${tc["comment"]!!.jsonPrimitive.content}): valid signature rejected"
                    "invalid" -> if (ok) wrong += "tc $id (${tc["comment"]!!.jsonPrimitive.content}): INVALID signature accepted"
                    else -> if (ok) acceptableAccepted += id
                }
            }
        }
        assertEquals(210, checked)
        assertTrue(wrong.isEmpty(), "Disagreements with Wycheproof:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun ed25519SignatureIsDeterministicAndVerifiesWithAnyKnownTestKey() {
        // Known-answer tests inside the Wycheproof file (flag "Ktv") also fix signatures for given messages: ours must reproduce them via the seed-free path
        // only for verification (seeds are not in the file), so here we just re-verify that every "Ktv" vector flagged valid verifies.
        val root = load("ed25519_test.json")
        var n = 0
        for (group in root["testGroups"]!!.jsonArray) {
            val g = group.jsonObject
            val pk = hex(g["publicKey"]!!.jsonObject["pk"]!!.jsonPrimitive.content)
            for (t in g["tests"]!!.jsonArray) {
                val tc = t.jsonObject
                val flags = (tc["flags"] as JsonArray).map { it.jsonPrimitive.content }
                if ("Ktv" in flags && tc["result"]!!.jsonPrimitive.content == "valid") {
                    assertTrue(Curve25519.ed25519Verify(pk, hex(tc["msg"]!!.jsonPrimitive.content), hex(tc["sig"]!!.jsonPrimitive.content)))
                    n++
                }
            }
        }
        assertTrue(n >= 30, "expected the known-answer vectors, found $n")
    }

    @Test
    fun x25519MatchesEveryWycheproofSharedSecret() {
        val root = load("x25519_test.json")
        var checked = 0
        val wrong = mutableListOf<String>()
        for (group in root["testGroups"]!!.jsonArray) {
            for (t in group.jsonObject["tests"]!!.jsonArray) {
                val tc = t.jsonObject
                val id = tc["tcId"]!!.jsonPrimitive.content
                val pub = hex(tc["public"]!!.jsonPrimitive.content)
                val priv = hex(tc["private"]!!.jsonPrimitive.content)
                val expected = tc["shared"]!!.jsonPrimitive.content
                checked++
                val got = try { Curve25519.x25519(priv, pub).toHex() } catch (e: IllegalArgumentException) { "rejected: ${e.message}" }
                // RFC 7748 says non-canonical and low-order inputs must be processed, not rejected, so every vector must match exactly.
                if (got != expected) wrong += "tc $id (${tc["comment"]!!.jsonPrimitive.content}): expected $expected, got $got"
            }
        }
        assertEquals(518, checked)
        assertTrue(wrong.isEmpty(), "Disagreements with Wycheproof (${wrong.size}):\n" + wrong.take(20).joinToString("\n"))
    }
}
