package com.sidescreen.host.protocol

/** Touch actions as sent by the client: 0 = down, 1 = move, 2 = up. */
object TouchAction {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
}

sealed interface ClientMessage {
    /** Normalized (0..1) coordinates relative to the streamed display. */
    data class Touch(
        val x1: Float,
        val y1: Float,
        val action: Int,
        val pointerCount: Int,
        val x2: Float = 0f,
        val y2: Float = 0f,
    ) : ClientMessage

    /** 8 opaque bytes (the client's little-endian nanoTime) to be echoed back in a pong. */
    class Ping(val timestamp: ByteArray) : ClientMessage

    data class KeyframeRequest(val force: Boolean) : ClientMessage

    data object SupportsFrameMetadata : ClientMessage

    data object AvcOnly : ClientMessage

    data class DecoderLimits(val limit: Size) : ClientMessage

    data class DecoderLimitTable(val table: Map<Int, Size>) : ClientMessage

    data object SupportsDesktopGeometry : ClientMessage
}

/**
 * Incremental parser for the client→host byte stream. Feed it whatever the
 * socket delivered; it yields every complete message and keeps the tail.
 *
 * Unknown types are skipped one byte at a time — the protocol's forward
 * compatibility relies on that (new payloads keep the high bit set).
 */
class ClientMessageParser(
    private val log: (String) -> Unit = {},
) {
    private var buffer = ByteArray(1024)
    private var start = 0
    private var end = 0

    private val available get() = end - start

    fun feed(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ): List<ClientMessage> {
        append(data, offset, length)
        val out = ArrayList<ClientMessage>(2)
        while (available > 0) {
            val msg = parseOne() ?: break
            if (msg !== Skipped) out += msg as ClientMessage
        }
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    private object Skipped

    /** Returns a message, [Skipped] for a consumed-but-ignored message, or null when more bytes are needed. */
    private fun parseOne(): Any? {
        when (val type = byteAt(0)) {
            WireMessage.TOUCH_EVENT -> {
                // [type 1][pointerCount 1][N * (x f32, y f32)][action i32], little-endian.
                if (available < 2) return null
                val pointerCount = byteAt(1)
                if (pointerCount != 1 && pointerCount != 2) {
                    log("Invalid touch pointer count: $pointerCount")
                    consume(1)
                    return Skipped
                }
                val size = 2 + pointerCount * 8 + 4
                if (available < size) return null
                val x1 = floatLeAt(2)
                val y1 = floatLeAt(6)
                val x2 = if (pointerCount == 2) floatLeAt(10) else 0f
                val y2 = if (pointerCount == 2) floatLeAt(14) else 0f
                val action = intLeAt(2 + pointerCount * 8)
                consume(size)
                return ClientMessage.Touch(x1, y1, action, pointerCount, x2, y2)
            }

            WireMessage.PING -> {
                if (available < 9) return null
                val ts = buffer.copyOfRange(start + 1, start + 9)
                consume(9)
                return ClientMessage.Ping(ts)
            }

            WireMessage.KEYFRAME_REQUEST -> {
                if (available < 2) return null
                val flags = byteAt(1)
                consume(2)
                return ClientMessage.KeyframeRequest(force = flags and 1 != 0)
            }

            WireMessage.CLIENT_SUPPORTS_FRAME_METADATA -> {
                consume(1)
                return ClientMessage.SupportsFrameMetadata
            }

            WireMessage.CLIENT_AVC_ONLY -> {
                consume(1)
                return ClientMessage.AvcOnly
            }

            WireMessage.CLIENT_DECODER_LIMITS -> {
                if (available < 5) return null
                val size = DecoderLimitWire.decodeSize(buffer, start + 1)
                consume(5)
                if (size == null) {
                    log("Malformed decoder-limits payload — ignoring")
                    return Skipped
                }
                if (size.width < CodecLimits.MIN_REPORTED_DIMENSION || size.height < CodecLimits.MIN_REPORTED_DIMENSION) {
                    return Skipped
                }
                return ClientMessage.DecoderLimits(size)
            }

            WireMessage.CLIENT_DECODER_LIMIT_TABLE -> {
                val payload = 4 * CodecLimits.TABLE_FPS.size
                if (available < 1 + payload) return null
                val sizes = CodecLimits.TABLE_FPS.indices.map { DecoderLimitWire.decodeSize(buffer, start + 1 + 4 * it) }
                consume(1 + payload)
                if (sizes.any { it == null }) {
                    log("Malformed decoder-limit-table payload — ignoring")
                    return Skipped
                }
                // Anything below QVGA-ish is a nonsense report — drop the whole table.
                if (sizes.any { it!!.width < CodecLimits.MIN_REPORTED_DIMENSION || it.height < CodecLimits.MIN_REPORTED_DIMENSION }) {
                    log("Decoder limit table has a nonsense entry — ignoring")
                    return Skipped
                }
                return ClientMessage.DecoderLimitTable(CodecLimits.TABLE_FPS.zip(sizes.map { it!! }).toMap())
            }

            WireMessage.CLIENT_SUPPORTS_DESKTOP_GEOMETRY -> {
                consume(1)
                return ClientMessage.SupportsDesktopGeometry
            }

            else -> {
                log("Unknown client input type: $type")
                consume(1)
                return Skipped
            }
        }
    }

    private fun byteAt(i: Int): Int = buffer[start + i].toInt() and 0xFF

    private fun intLeAt(i: Int): Int = byteAt(i) or (byteAt(i + 1) shl 8) or (byteAt(i + 2) shl 16) or (byteAt(i + 3) shl 24)

    private fun floatLeAt(i: Int): Float = Float.fromBits(intLeAt(i))

    private fun consume(n: Int) {
        start += n
    }

    private fun append(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (end + length > buffer.size) {
            // Compact first; grow only if still short.
            val live = available
            val target = if (live + length > buffer.size) maxOf(buffer.size * 2, live + length) else buffer.size
            val nb = if (target != buffer.size) ByteArray(target) else buffer
            buffer.copyInto(nb, 0, start, end)
            buffer = nb
            start = 0
            end = live
        }
        data.copyInto(buffer, end, offset, offset + length)
        end += length
    }
}
