package dev.patchbay.core.adapter.rtsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtspAuthTest {
    @Test
    fun digestMatchesTheRfc2617Example() {
        // RFC 2617 §3.5: Mufasa / "Circle Of Life", GET /dir/index.html, qop=auth.
        val c = RtspProtocol.Challenge.Digest("testrealm@host.com", "dcd98b7102dd2f0e8b11d0f600bfb0c093", true, "5ccc069c403ebaf9f0171e9517f40e41")
        val h = RtspAuth.digest(c, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", "0a4f113b")
        assertTrue(h.contains("response=\"6629fae49393a05397450978507c4ef1\""), h)
        assertTrue(h.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""), h)
        assertTrue(h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""), h)
    }

    @Test
    fun basicIsBase64OfUserColonPassword() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", RtspAuth.basic("Aladdin", "open sesame"))
    }
}
