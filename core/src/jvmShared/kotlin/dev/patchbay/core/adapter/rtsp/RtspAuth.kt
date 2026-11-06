package dev.patchbay.core.adapter.rtsp

import java.security.MessageDigest
import java.util.Base64

/**
 * RTSP Basic and Digest (RFC 2617, MD5) authorisation headers. MD5 here is the protocol's own challenge
 * hash, which cameras dictate; it protects nothing on its own. RTSP on the LAN is cleartext, which is why
 * Digest is preferred and why the credentials only ever go to a camera's own LAN address.
 */
internal object RtspAuth {

    fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    fun authorization(c: RtspProtocol.Challenge, user: String, password: String, method: String, uri: String, cnonce: String): String =
        when (c) {
            is RtspProtocol.Challenge.Basic -> basic(user, password)
            is RtspProtocol.Challenge.Digest -> digest(c, user, password, method, uri, cnonce)
        }

    fun digest(c: RtspProtocol.Challenge.Digest, user: String, password: String, method: String, uri: String, cnonce: String): String {
        val ha1 = md5("$user:${c.realm}:$password")
        val ha2 = md5("$method:$uri")
        val nc = "00000001"
        val response = if (c.qopAuth) md5("$ha1:${c.nonce}:$nc:$cnonce:auth:$ha2") else md5("$ha1:${c.nonce}:$ha2")
        return buildString {
            append("Digest username=\"").append(user).append("\", realm=\"").append(c.realm).append("\", nonce=\"").append(c.nonce)
            append("\", uri=\"").append(uri).append("\", response=\"").append(response).append('"')
            if (c.qopAuth) append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
            c.opaque?.let { append(", opaque=\"").append(it).append('"') }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
