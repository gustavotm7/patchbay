package dev.patchbay.core.adapter.androidtv

import dev.patchbay.core.model.RemoteKey
import dev.patchbay.core.result.PatchbayResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull

class AndroidTvProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun keyPressMatchesAHandBuiltMessage() {
        // RemoteMessage{ remote_key_inject(10){ key_code(1)=19 DPAD_UP, direction(2)=3 SHORT } } = 52 04 08 13 10 03
        assertEquals("520408131003", hex(AndroidTvProtocol.remoteKey(RemoteKey.UP)))
        assertEquals("520408551003", hex(AndroidTvProtocol.remoteKey(RemoteKey.PLAY_PAUSE))) // 85 = 0x55
    }

    @Test
    fun framingIsAVarintLength() {
        assertEquals("03aabbcc", hex(AndroidTvProtocol.frame(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()))))
        val big = AndroidTvProtocol.frame(ByteArray(300))
        assertEquals("ac02", hex(big.copyOf(2))) // 300 = 0xAC 0x02
    }

    @Test
    fun pairingMessagesCarryTheReferenceFieldNumbers() {
        // OuterMessage: protocol_version(1)=2 -> 08 02, status(2)=200 -> 10 c8 01; then the payload field.
        val head = "080210c801"
        // pairing_request(10){ service_name(1)="atvremote", client_name(2)="P" }
        assertEquals(head + "520e" + "0a09" + "61747672656d6f7465" + "1201" + "50", hex(AndroidTvProtocol.pairingRequest("P")))
        // options(20){ input_encodings(1){ type(1)=3 HEX, symbol_length(2)=6 }, preferred_role(3)=1 INPUT }
        assertEquals(head + "a20108" + "0a04" + "08031006" + "1801", hex(AndroidTvProtocol.pairingOptions()))
        // configuration(30){ encoding(1){3, 6}, client_role(2)=1 }
        assertEquals(head + "f20108" + "0a04" + "08031006" + "1001", hex(AndroidTvProtocol.pairingConfiguration()))
        // secret(40){ secret(1) }
        assertEquals(head + "c20205" + "0a03010203", hex(AndroidTvProtocol.pairingSecretMessage(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun secretIsTheDocumentedSha256AndChecksTheFirstCodeByte() {
        fun sha(b: ByteArray): ByteArray = ByteArray(32) { i -> (b.fold(i) { acc, x -> acc * 31 + x }.toByte()) } // stand-in hash: deterministic, 32 bytes
        val cm = byteArrayOf(1, 2, 3); val ce = byteArrayOf(1, 0, 1); val sm = byteArrayOf(9, 8, 7); val se = byteArrayOf(1, 0, 1)
        val tail = byteArrayOf(0x12, 0x34)
        val digest = sha(cm + ce + sm + se + tail)
        val good = "%02X".format(digest[0].toInt() and 0xFF) + "1234"
        assertContentEquals(digest, AndroidTvProtocol.pairingSecret(cm, ce, sm, se, good, ::sha))
        assertContentEquals(digest, AndroidTvProtocol.pairingSecret(cm, ce, sm, se, good.lowercase(), ::sha))
        val wrongFirst = "%02X".format((digest[0].toInt() + 1) and 0xFF) + "1234"
        assertNull(AndroidTvProtocol.pairingSecret(cm, ce, sm, se, wrongFirst, ::sha))
        assertNull(AndroidTvProtocol.pairingSecret(cm, ce, sm, se, "12345", ::sha))
        assertNull(AndroidTvProtocol.pairingSecret(cm, ce, sm, se, "12345G", ::sha))
    }

    @Test
    fun parsesPairingReplies() {
        fun reply(field: Int, status: Int = 200): ByteArray = byteArrayOf(0x08, 0x02, 0x10) + varint(status) + varint((field shl 3) or 2) + byteArrayOf(0x00)
        assertEquals(AndroidTvProtocol.PairingStep.REQUEST_ACK, (AndroidTvProtocol.parsePairing(reply(11)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.PairingStep.OPTIONS, (AndroidTvProtocol.parsePairing(reply(20)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.PairingStep.CONFIGURATION_ACK, (AndroidTvProtocol.parsePairing(reply(31)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.PairingStep.SECRET_ACK, (AndroidTvProtocol.parsePairing(reply(41)) as PatchbayResult.Ok).value)
        val bad = AndroidTvProtocol.parsePairing(reply(41, 402))
        assertIs<PatchbayResult.Err>(bad)
        assertEquals("the TV rejected the code", bad.error.message)
        assertIs<PatchbayResult.Err>(AndroidTvProtocol.parsePairing(reply(99)))
        assertIs<PatchbayResult.Err>(AndroidTvProtocol.parsePairing(byteArrayOf(0x08)))
    }

    private fun varint(v0: Int): ByteArray { var v = v0; val o = ArrayList<Byte>(); while (v >= 0x80) { o += ((v and 0x7f) or 0x80).toByte(); v = v ushr 7 }; o += v.toByte(); return o.toByteArray() }

    @Test
    fun parsesRemoteMessagesAndIgnoresTheRest() {
        // configure(1){ code1(1)=611 }, ping_request(8){ val1(1)=7, val2(2)=1 }, start(40){ started(1)=1 }, set_active(2){ active(1)=611 }
        assertEquals(AndroidTvProtocol.Incoming.Configure(611), (AndroidTvProtocol.parseRemote(byteArrayOf(0x0a, 0x03, 0x08, 0xe3.toByte(), 0x04)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.Incoming.Ping(7), (AndroidTvProtocol.parseRemote(byteArrayOf(0x42, 0x04, 0x08, 0x07, 0x10, 0x01)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.Incoming.Started(true), (AndroidTvProtocol.parseRemote(byteArrayOf(0xc2.toByte(), 0x02, 0x02, 0x08, 0x01)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.Incoming.SetActive(611), (AndroidTvProtocol.parseRemote(byteArrayOf(0x12, 0x03, 0x08, 0xe3.toByte(), 0x04)) as PatchbayResult.Ok).value)
        assertEquals(AndroidTvProtocol.Incoming.Other, (AndroidTvProtocol.parseRemote(byteArrayOf(0xa2.toByte(), 0x01, 0x00)) as PatchbayResult.Ok).value) // an IME message: ignored
    }

    @Test
    fun ourRemoteRepliesCarryTheReferenceFields() {
        // Only features the TV also offers are echoed (611 offered: ping, key, power, volume are kept; app links are not).
        assertEquals(99, AndroidTvProtocol.FEATURES)
        assertTrue(hex(AndroidTvProtocol.remoteConfigure(611)).startsWith("0a") && hex(AndroidTvProtocol.remoteConfigure(611)).contains("0863"))
        assertTrue(hex(AndroidTvProtocol.remoteConfigure(0)).contains("0800"))
        assertEquals("4a020807", hex(AndroidTvProtocol.remotePingResponse(7))) // remote_ping_response(9){ val1(1)=7 }
        assertEquals("120308e304", hex(AndroidTvProtocol.remoteSetActive(611))) // remote_set_active(2){ active(1)=611 }
    }

    @Test
    fun keyCodesFollowTheReferenceEnum() {
        val expect = mapOf(
            RemoteKey.HOME to 3, RemoteKey.BACK to 4, RemoteKey.UP to 19, RemoteKey.DOWN to 20, RemoteKey.LEFT to 21, RemoteKey.RIGHT to 22,
            RemoteKey.SELECT to 23, RemoteKey.VOLUME_UP to 24, RemoteKey.VOLUME_DOWN to 25, RemoteKey.POWER to 26, RemoteKey.PLAY_PAUSE to 85, RemoteKey.MUTE to 164,
        )
        for (k in RemoteKey.entries) assertEquals(expect.getValue(k), AndroidTvProtocol.keyCode(k), k.name)
    }

    @Test
    fun mutatedInputNeverThrows() {
        val rnd = Random(0x41545652)
        val seeds = listOf(byteArrayOf(0x0a, 0x03, 0x08, 0xe3.toByte(), 0x04), byteArrayOf(0x08, 0x02, 0x10, 0xc8.toByte(), 0x01, 0x5a, 0x02, 0x0a, 0x00), AndroidTvProtocol.remoteKey(RemoteKey.HOME))
        repeat(25_000) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(3)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            AndroidTvProtocol.parseRemote(cut); AndroidTvProtocol.parsePairing(cut)
        }
    }
}
