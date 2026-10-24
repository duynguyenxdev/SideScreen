package com.sidescreen.host.protocol

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Wire encoding shared by the decoder-limit messages (types 11 and 14): each
 * dimension is two bytes of 7 data bits with the high bit always set, so a
 * host that skips unknown input one byte at a time never mistakes a payload
 * byte for a message type.
 */
object DecoderLimitWire {
    const val MAX_DIMENSION = 16383

    fun encodeSize(size: Size): ByteArray {
        val w = size.width.coerceIn(0, MAX_DIMENSION)
        val h = size.height.coerceIn(0, MAX_DIMENSION)
        return byteArrayOf(
            (0x80 or ((w shr 7) and 0x7F)).toByte(),
            (0x80 or (w and 0x7F)).toByte(),
            (0x80 or ((h shr 7) and 0x7F)).toByte(),
            (0x80 or (h and 0x7F)).toByte(),
        )
    }

    /** Null unless every high bit is set. */
    fun decodeSize(
        bytes: ByteArray,
        offset: Int = 0,
    ): Size? {
        if (offset < 0 || bytes.size < offset + 4) return null
        val b = IntArray(4) { bytes[offset + it].toInt() and 0xFF }
        if (b.any { it and 0x80 == 0 }) return null
        return Size(((b[0] and 0x7F) shl 7) or (b[1] and 0x7F), ((b[2] and 0x7F) shl 7) or (b[3] and 0x7F))
    }
}

object CodecLimits {
    /** Conservative floor every AVC hardware decoder meets (H.264 level 4.x). */
    const val AVC_MAX_WIDTH = 1920
    const val AVC_MAX_HEIGHT = 1088

    /** Frame rate the client measures its advertised type-11 limit at. */
    const val CLIENT_LIMIT_REFERENCE_FPS = 60

    /** Frame rates a type-14 client measures its sustainable size at, in wire order. */
    val TABLE_FPS = listOf(30, 60, 120)

    /** Sizes below this are nonsense reports and are ignored. */
    const val MIN_REPORTED_DIMENSION = 256

    /**
     * Scale [size] down to fit within [maxWidth]x[maxHeight] preserving aspect
     * ratio, flooring each side to a multiple of 16. Sizes already within the
     * limit pass through unchanged.
     */
    fun clamp(
        size: Size,
        maxWidth: Int,
        maxHeight: Int,
    ): Size {
        if (size.width <= maxWidth && size.height <= maxHeight) return size
        val scale = minOf(maxWidth.toDouble() / size.width, maxHeight.toDouble() / size.height)
        return Size(align16(size.width * scale), align16(size.height * scale))
    }

    fun clampForAvc(size: Size): Size = clamp(size, AVC_MAX_WIDTH, AVC_MAX_HEIGHT)

    /**
     * The client's limit is a blocks-per-second budget expressed as a frame
     * size at [referenceFps]. Faster streams shrink the box's area by
     * reference/fps; slower ones do not grow it.
     */
    fun scaleLimit(
        limit: Size,
        fps: Int,
        referenceFps: Int = CLIENT_LIMIT_REFERENCE_FPS,
    ): Size {
        if (fps <= referenceFps || limit.width <= 0 || limit.height <= 0) return limit
        val k = sqrt(referenceFps.toDouble() / fps)
        return Size(align16(limit.width * k), align16(limit.height * k))
    }

    /**
     * The ceiling to encode within at [fps]. With a per-rate table (type 14)
     * it is the entry measured at the lowest rate at or above [fps]; beyond
     * the fastest rate, that entry scaled down by area. Without a table, the
     * single limit scaled. Null when the client reported nothing.
     */
    fun budget(
        limit: Size?,
        table: Map<Int, Size>?,
        fps: Int,
    ): Size? {
        if (!table.isNullOrEmpty()) {
            table.keys.filter { it >= fps }.minOrNull()?.let { return table.getValue(it) }
            val top = table.keys.max()
            return scaleLimit(table.getValue(top), fps, top)
        }
        return limit?.let { scaleLimit(it, fps) }
    }

    /**
     * Clamp into a client-reported ceiling, transposing the box when its
     * orientation differs from the capture's: the ceiling stands for a
     * macroblock area budget, indifferent to which side is longer.
     */
    fun clampToClientLimit(
        size: Size,
        limit: Size,
    ): Size {
        val box = if ((size.height > size.width) == (limit.height > limit.width)) limit else Size(limit.height, limit.width)
        return clamp(size, box.width, box.height)
    }

    /**
     * Encode dimensions for a codec: the captured size, clamped to the
     * client's reported ceiling when known, else to the conservative AVC
     * floor when streaming H.264.
     */
    fun encodeSize(
        captured: Size,
        codec: StreamCodec,
        clientLimit: Size?,
        clientLimitTable: Map<Int, Size>?,
        fps: Int,
    ): Size {
        budget(clientLimit, clientLimitTable, fps)?.let { return clampToClientLimit(captured, it) }
        return when (codec) {
            StreamCodec.HEVC -> captured
            StreamCodec.H264 -> clampForAvc(captured)
        }
    }

    private fun align16(v: Double): Int = maxOf(16, v.roundToInt() and 15.inv())
}
