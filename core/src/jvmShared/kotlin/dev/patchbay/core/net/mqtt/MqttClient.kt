package dev.patchbay.core.net.mqtt

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A minimal MQTT 3.1.1 client (QoS 0 only) over an already-connected socket, normally a pinned TLS socket
 * from NetGuard.connectTls. It covers what LAN devices like Bambu printers need: CONNECT with username/password,
 * one SUBSCRIBE, PUBLISH, and PINGREQ.
 *
 * Inbound packets are bounded ([MAX_PACKET]); malformed lengths and unknown packet types end the session.
 */
public class MqttClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    public sealed interface Packet {
        public class Publish(public val topic: String, public val payload: ByteArray) : Packet
        public data object PingResp : Packet
        public data object SubAck : Packet
    }

    private var nextPacketId = 1

    @Synchronized
    public fun subscribe(topic: String): PatchbayResult<Unit> = write {
        val body = ByteArrayOutputStream()
        val id = nextPacketId++ and 0xFFFF
        body.write(id shr 8); body.write(id and 0xFF)
        writeString(body, topic)
        body.write(0) // QoS 0
        packet(0x82, body.toByteArray())
    }

    @Synchronized
    public fun publish(topic: String, payload: ByteArray): PatchbayResult<Unit> = write {
        val body = ByteArrayOutputStream()
        writeString(body, topic)
        body.write(payload)
        packet(0x30, body.toByteArray())
    }

    @Synchronized
    public fun ping(): PatchbayResult<Unit> = write { packet(0xC0, ByteArray(0)) }

    /** Reads the next packet (blocking up to the socket's soTimeout). `Ok(null)` = connection closed. */
    public fun receive(): PatchbayResult<Packet?> = try {
        when (val r = readPacket(input)) {
            null -> PatchbayResult.Ok(null)
            else -> {
                val (type, body) = r
                when (type shr 4) {
                    3 -> {
                        val qos = (type shr 1) and 0x3
                        if (body.size < 2) bad("short PUBLISH")
                        else {
                            val tlen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                            val start = 2 + tlen + if (qos > 0) 2 else 0
                            if (2 + tlen > body.size || start > body.size) bad("PUBLISH topic overruns packet")
                            else PatchbayResult.Ok(Packet.Publish(body.decodeToString(2, 2 + tlen), body.copyOfRange(start, body.size)))
                        }
                    }
                    9 -> PatchbayResult.Ok(Packet.SubAck)
                    13 -> PatchbayResult.Ok(Packet.PingResp)
                    else -> bad("unexpected packet type ${type shr 4}")
                }
            }
        }
    } catch (e: SocketTimeoutException) {
        PatchbayResult.Err(PatchbayError.Timeout("mqtt read timed out"))
    } catch (e: EOFException) {
        PatchbayResult.Ok(null)
    } catch (e: IOException) {
        PatchbayResult.Err(PatchbayError.Unreachable("mqtt read failed: ${e.javaClass.simpleName}"))
    }

    private inline fun write(block: () -> Unit): PatchbayResult<Unit> = try {
        block()
        PatchbayResult.Ok(Unit)
    } catch (e: IOException) {
        PatchbayResult.Err(PatchbayError.Unreachable("mqtt write failed: ${e.javaClass.simpleName}"))
    }

    private fun packet(header: Int, body: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(header)
        writeLength(out, body.size)
        out.write(body)
        output.write(out.toByteArray())
        output.flush()
    }

    override fun close() {
        runCatching { synchronized(this) { packet(0xE0, ByteArray(0)) } } // DISCONNECT
        socket.close()
    }

    public companion object {
        public const val MAX_PACKET: Int = 512 * 1024

        /**
         * Sends CONNECT and waits for CONNACK. Return code 4/5 (bad credentials / not authorised) maps to
         * [PatchbayError.Unauthorized]. [password] is a secret: never logged.
         */
        public fun connect(socket: Socket, clientId: String, username: String, password: String, keepAliveSec: Int = 60): PatchbayResult<MqttClient> {
            require(clientId.length in 1..23 && clientId.all { it.isLetterOrDigit() || it == '-' })
            return try {
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                val body = ByteArrayOutputStream()
                writeString(body, "MQTT")
                body.write(4) // protocol level 3.1.1
                body.write(0x80 or 0x40 or 0x02) // username, password, clean session
                body.write(keepAliveSec shr 8); body.write(keepAliveSec and 0xFF)
                writeString(body, clientId)
                writeString(body, username)
                writeString(body, password)
                val pkt = ByteArrayOutputStream()
                pkt.write(0x10); writeLength(pkt, body.size()); pkt.write(body.toByteArray())
                out.write(pkt.toByteArray()); out.flush()

                val (type, ack) = readPacket(inp) ?: return PatchbayResult.Err(PatchbayError.Unreachable("connection closed before CONNACK"))
                if (type shr 4 != 2 || ack.size != 2) return bad("expected CONNACK")
                when (ack[1].toInt()) {
                    0 -> PatchbayResult.Ok(MqttClient(socket, inp, out))
                    4, 5 -> PatchbayResult.Err(PatchbayError.Unauthorized("the device rejected the access code"))
                    else -> bad("CONNACK refused (${ack[1].toInt()})")
                }
            } catch (e: SocketTimeoutException) {
                PatchbayResult.Err(PatchbayError.Timeout("mqtt connect timed out"))
            } catch (e: IOException) {
                PatchbayResult.Err(PatchbayError.Unreachable("mqtt connect failed: ${e.javaClass.simpleName}"))
            }
        }

        private fun writeString(out: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size <= 0xFFFF)
            out.write(b.size shr 8); out.write(b.size and 0xFF); out.write(b)
        }

        private fun writeLength(out: ByteArrayOutputStream, len: Int) {
            var x = len
            do {
                var digit = x % 128
                x /= 128
                if (x > 0) digit = digit or 0x80
                out.write(digit)
            } while (x > 0)
        }

        /** Returns (first byte, body) or null on clean EOF. Throws EOFException mid-packet. */
        private fun readPacket(inp: InputStream): Pair<Int, ByteArray>? {
            val type = inp.read()
            if (type < 0) return null
            var mult = 1
            var len = 0
            var i = 0
            while (true) {
                val d = inp.read()
                if (d < 0) throw EOFException()
                len += (d and 0x7F) * mult
                if (d and 0x80 == 0) break
                mult *= 128
                if (++i >= 4) throw IOException("malformed remaining length")
            }
            if (len > MAX_PACKET) throw IOException("packet too large")
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = inp.read(body, off, len - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return type to body
        }

        private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("mqtt: $why"))
    }
}
