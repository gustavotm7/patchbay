package dev.patchbay.core.net.ws

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A minimal RFC 6455 WebSocket client over an already-connected socket (normally a pinned TLS socket from
 * NetGuard.connectTls). It never opens connections itself.
 *
 * It sends text messages and receives them, answering pings and closes. Everything the server sends is
 * bounded: 8 KiB of handshake headers, 256 KiB messages, unmasked frames only, and no reserved bits or
 * opcodes.
 */
public class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    private val random = SecureRandom()

    @Synchronized
    public fun sendText(text: String): PatchbayResult<Unit> = try {
        writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
        PatchbayResult.Ok(Unit)
    } catch (e: IOException) {
        PatchbayResult.Err(PatchbayError.Unreachable("websocket send failed: ${e.javaClass.simpleName}"))
    }

    /**
     * Blocks until a text message arrives or the read times out (socket soTimeout). Returns `Ok(null)` when
     * the server closed the connection.
     */
    public fun receiveText(): PatchbayResult<String?> {
        val message = java.io.ByteArrayOutputStream()
        var textMessage = false
        try {
            while (true) {
                val b0 = readByte()
                val b1 = readByte()
                val fin = b0 and 0x80 != 0
                if (b0 and 0x70 != 0) return bad("reserved bits set")
                val opcode = b0 and 0x0F
                if (b1 and 0x80 != 0) return bad("server frames must not be masked")
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or readByte().toLong() }
                }
                if (len < 0 || len > MAX_MESSAGE) return bad("frame too large")
                val payload = readExactly(len.toInt())
                when (opcode) {
                    OP_TEXT, OP_CONT -> {
                        if (opcode == OP_TEXT) textMessage = true
                        if (!textMessage) return bad("continuation without start")
                        if (message.size() + payload.size > MAX_MESSAGE) return bad("message too large")
                        message.write(payload)
                        if (fin) return PatchbayResult.Ok(decodeUtf8(message.toByteArray()) ?: return bad("invalid UTF-8"))
                    }
                    OP_BINARY -> return bad("binary messages not expected")
                    OP_PING -> { if (payload.size > 125) return bad("ping too large"); synchronized(this) { writeFrame(OP_PONG, payload) } }
                    OP_PONG -> {}
                    OP_CLOSE -> {
                        runCatching { synchronized(this) { writeFrame(OP_CLOSE, ByteArray(0)) } }
                        return PatchbayResult.Ok(null)
                    }
                    else -> return bad("unknown opcode $opcode")
                }
            }
        } catch (e: SocketTimeoutException) {
            return PatchbayResult.Err(PatchbayError.Timeout("websocket read timed out"))
        } catch (e: EOFException) {
            return PatchbayResult.Ok(null)
        } catch (e: IOException) {
            return PatchbayResult.Err(PatchbayError.Unreachable("websocket read failed: ${e.javaClass.simpleName}"))
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xFF) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return out
    }

    override fun close() {
        runCatching { synchronized(this) { writeFrame(OP_CLOSE, ByteArray(0)) } }
        socket.close()
    }

    public companion object {
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        public const val MAX_MESSAGE: Int = 256 * 1024
        private const val MAX_HANDSHAKE = 8 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Performs the opening handshake on [socket] and verifies Sec-WebSocket-Accept. */
        public fun handshake(socket: Socket, hostHeader: String, path: String = "/"): PatchbayResult<WebSocketClient> {
            require(hostHeader.none { it == '\r' || it == '\n' } && path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == ' ' })
            return try {
                val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                out.write(
                    ("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                out.flush()
                val head = readHead(inp) ?: return bad("handshake response too large or truncated")
                val lines = head.split("\r\n")
                if (!lines.first().matches(Regex("^HTTP/1\\.1 101\\b.*"))) return bad("server refused the upgrade")
                val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
                val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
                if (headers["sec-websocket-accept"] != expected) return bad("bad Sec-WebSocket-Accept")
                if (headers["upgrade"]?.lowercase() != "websocket") return bad("missing Upgrade header")
                PatchbayResult.Ok(WebSocketClient(socket, inp, out))
            } catch (e: IOException) {
                PatchbayResult.Err(PatchbayError.Unreachable("websocket handshake failed: ${e.javaClass.simpleName}"))
            }
        }

        private fun readHead(inp: InputStream): String? {
            val buf = java.io.ByteArrayOutputStream()
            var last4 = 0
            while (buf.size() < MAX_HANDSHAKE) {
                val b = inp.read()
                if (b < 0) return null
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.ISO_8859_1).trimEnd()
            }
            return null
        }

        private fun decodeUtf8(b: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
        }.getOrNull()

        private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("websocket: $why"))
    }
}
