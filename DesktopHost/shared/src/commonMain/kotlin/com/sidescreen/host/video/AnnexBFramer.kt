package com.sidescreen.host.video

import com.sidescreen.host.protocol.StreamCodec

/** One encoded picture in Annex-B form (start codes included). */
class AccessUnit(
    val data: ByteArray,
    val isKeyframe: Boolean,
)

/** NAL-unit classification for H.264 and HEVC. */
internal object Nal {
    fun type(
        codec: StreamCodec,
        header: Int,
    ): Int =
        when (codec) {
            StreamCodec.HEVC -> (header shr 1) and 0x3F
            StreamCodec.H264 -> header and 0x1F
        }

    fun isVcl(
        codec: StreamCodec,
        type: Int,
    ): Boolean =
        when (codec) {
            StreamCodec.HEVC -> type in 0..31
            StreamCodec.H264 -> type in 1..5
        }

    /** Same test the Android client uses (StreamClient.isSyncFrame). */
    fun isKeyframe(
        codec: StreamCodec,
        type: Int,
    ): Boolean =
        when (codec) {
            StreamCodec.HEVC -> type in 16..21
            StreamCodec.H264 -> type == 5
        }

    fun isParameterSet(
        codec: StreamCodec,
        type: Int,
    ): Boolean =
        when (codec) {
            StreamCodec.HEVC -> type in 32..34
            StreamCodec.H264 -> type == 7 || type == 8
        }

    /** Non-VCL NAL types that may only appear before the first VCL NAL of an access unit. */
    fun startsAccessUnit(
        codec: StreamCodec,
        type: Int,
    ): Boolean =
        when (codec) {
            StreamCodec.HEVC -> type in 32..35 || type == 39 || type in 41..44 || type in 48..55
            StreamCodec.H264 -> type in 6..9 || type in 14..18
        }
}

/**
 * Splits an Annex-B elementary stream (as written by `ffmpeg -f hevc|h264`)
 * into access units.
 *
 * A NAL unit is only known to be complete once the next start code arrives,
 * so by default an access unit is emitted when the next one begins — one
 * frame of latency. [flushIdle] removes that latency: the caller invokes it
 * when the encoder has gone quiet (it writes each packet in one burst), and
 * the in-flight NAL is assumed complete. If that guess turns out wrong — the
 * next bytes continue the previous NAL or slice — [takeDesync] reports it so
 * the stream can be held until the next keyframe.
 */
class AnnexBFramer(
    private val codec: StreamCodec,
    private val maxAccessUnitBytes: Int = 32 * 1024 * 1024,
) {
    private val nal = ByteBuf() // bytes of the in-flight NAL, starting at its start code
    private val au = ByteBuf() // completed NALs of the current access unit
    private var auHasVcl = false
    private var auIsKeyframe = false
    private var auHasParameterSets = false
    private var scanFrom = 0
    private var flushedEarly = false
    private var desync = false

    /** Latest parameter sets, by NAL type, re-sent ahead of keyframes that lack them. */
    private val parameterSets = linkedMapOf<Int, ByteArray>()

    fun push(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): List<AccessUnit> {
        val out = ArrayList<AccessUnit>(1)
        nal.append(data, offset, length)

        if (flushedEarly) {
            if (nal.size < 4) return out
            if (startCodeLength(0) == 0) {
                // The early-flushed NAL was not finished after all.
                desync = true
                dropToNextStartCode()
                if (nal.size == 0) return out
            }
            flushedEarly = false
        }

        while (true) {
            emitIfInFlightStartsNewUnit(out)
            val next = findStartCode(maxOf(scanFrom, 3))
            if (next < 0) {
                scanFrom = maxOf(3, nal.size - 3)
                break
            }
            completeNal(next, out)
            scanFrom = 3
        }
        if (au.size + nal.size > maxAccessUnitBytes) {
            // Runaway input (no start codes): drop it rather than grow forever.
            au.clear()
            nal.clear()
            resetAccessUnit()
            desync = true
        }
        return out
    }

    /** Whether a picture NAL is in flight, i.e. [flushIdle] would emit something. */
    val hasPendingPicture: Boolean
        get() {
            val scLen = startCodeLength(0)
            return scLen != 0 && nal.size > scLen + 2 && Nal.isVcl(codec, Nal.type(codec, nal[scLen].toInt() and 0xFF))
        }

    /**
     * Treat the in-flight NAL as complete and emit what is finished. Only a
     * picture (VCL) NAL is completed this way: the encoder writes a whole
     * access unit per packet, so a stall right after a parameter set or SEI
     * means the packet is still being written.
     */
    fun flushIdle(): List<AccessUnit> {
        val scLen = startCodeLength(0)
        if (scLen == 0 || nal.size <= scLen + 2) return emptyList()
        if (!Nal.isVcl(codec, Nal.type(codec, nal[scLen].toInt() and 0xFF))) return emptyList()
        val out = ArrayList<AccessUnit>(2)
        completeNal(nal.size, out)
        scanFrom = 3
        flushedEarly = true
        expectFirstSliceAfterFlush = true
        if (auHasVcl) out += emitAccessUnit()
        return out
    }

    /**
     * Emit the current access unit as soon as the in-flight NAL's header shows
     * it begins the next one, instead of waiting for that NAL to complete.
     */
    private fun emitIfInFlightStartsNewUnit(out: MutableList<AccessUnit>) {
        if (!auHasVcl) return
        val scLen = startCodeLength(0)
        val needed = scLen + if (codec == StreamCodec.HEVC) 3 else 2
        if (scLen == 0 || nal.size < needed) return
        val type = Nal.type(codec, nal[scLen].toInt() and 0xFF)
        val vcl = Nal.isVcl(codec, type)
        if ((vcl && isFirstSlice(scLen)) || (!vcl && Nal.startsAccessUnit(codec, type))) out += emitAccessUnit()
    }

    /** True once if early flushing emitted a truncated picture since the last call. */
    fun takeDesync(): Boolean = desync.also { desync = false }

    private fun completeNal(
        endExclusive: Int,
        out: MutableList<AccessUnit>,
    ) {
        val scLen = startCodeLength(0)
        if (scLen == 0 || endExclusive <= scLen) {
            // Leading garbage before the first start code.
            nal.discardFront(endExclusive)
            return
        }
        val header = nal[scLen].toInt() and 0xFF
        val type = Nal.type(codec, header)
        val vcl = Nal.isVcl(codec, type)
        val firstSlice = vcl && isFirstSlice(scLen)

        if (flushedEarlyVclContinuation(vcl, firstSlice)) desync = true

        val beginsNew = (vcl && firstSlice) || (!vcl && Nal.startsAccessUnit(codec, type))
        if (beginsNew && auHasVcl) out += emitAccessUnit()

        if (Nal.isParameterSet(codec, type)) {
            parameterSets[type] = nal.copyRange(0, endExclusive)
            auHasParameterSets = true
        }
        if (vcl) {
            auHasVcl = true
            if (Nal.isKeyframe(codec, type)) auIsKeyframe = true
        }
        au.append(nal.array, 0, endExclusive)
        nal.discardFront(endExclusive)
    }

    private var expectFirstSliceAfterFlush = false

    private fun flushedEarlyVclContinuation(
        vcl: Boolean,
        firstSlice: Boolean,
    ): Boolean {
        val suspicious = expectFirstSliceAfterFlush && vcl && !firstSlice
        if (vcl) expectFirstSliceAfterFlush = false
        return suspicious
    }

    private fun emitAccessUnit(): AccessUnit {
        val bytes =
            if (auIsKeyframe && !auHasParameterSets && parameterSets.isNotEmpty()) {
                val ps = ByteBuf()
                parameterSets.values.forEach { ps.append(it, 0, it.size) }
                ps.append(au.array, 0, au.size)
                ps.copyRange(0, ps.size)
            } else {
                au.copyRange(0, au.size)
            }
        val unit = AccessUnit(bytes, auIsKeyframe)
        au.clear()
        resetAccessUnit()
        return unit
    }

    private fun resetAccessUnit() {
        auHasVcl = false
        auIsKeyframe = false
        auHasParameterSets = false
    }

    private fun isFirstSlice(scLen: Int): Boolean {
        val payload =
            when (codec) {
                StreamCodec.HEVC -> scLen + 2 // 2-byte NAL header, then first_slice_segment_in_pic_flag
                StreamCodec.H264 -> scLen + 1 // first_mb_in_slice ue(v): a leading 1 bit encodes 0
            }
        return payload < nal.size && (nal[payload].toInt() and 0x80) != 0
    }

    /** Index of the next start code at or after [from] (including a 4-byte code's leading zero), or -1. */
    private fun findStartCode(from: Int): Int {
        val a = nal.array
        var i = from
        val limit = nal.size - 3
        while (i <= limit) {
            if ((a[i + 2].toInt() and 0xFF) > 1) {
                i += 3
                continue
            }
            if (a[i] == 0.toByte() && a[i + 1] == 0.toByte() && a[i + 2] == 1.toByte()) {
                return if (i > 0 && a[i - 1] == 0.toByte() && i - 1 >= 3) i - 1 else i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(at: Int): Int {
        val a = nal.array
        val n = nal.size - at
        if (n >= 3 && a[at] == 0.toByte() && a[at + 1] == 0.toByte() && a[at + 2] == 1.toByte()) return 3
        if (n >= 4 && a[at] == 0.toByte() && a[at + 1] == 0.toByte() && a[at + 2] == 0.toByte() && a[at + 3] == 1.toByte()) return 4
        return 0
    }

    private fun dropToNextStartCode() {
        val next = findStartCode(0)
        if (next < 0) {
            // Keep the last two bytes: they may begin a start code.
            nal.discardFront(maxOf(0, nal.size - 2))
        } else {
            nal.discardFront(next)
        }
        // The partial picture was already sent; whatever follows of it is junk.
        au.clear()
        resetAccessUnit()
        scanFrom = 0
    }
}

/** Minimal growable byte buffer. */
internal class ByteBuf(
    initial: Int = 256 * 1024,
) {
    var array = ByteArray(initial)
        private set
    var size = 0
        private set

    operator fun get(i: Int): Byte = array[i]

    fun append(
        src: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensure(size + length)
        src.copyInto(array, size, offset, offset + length)
        size += length
    }

    fun discardFront(n: Int) {
        if (n <= 0) return
        if (n >= size) {
            size = 0
            return
        }
        array.copyInto(array, 0, n, size)
        size -= n
    }

    fun copyRange(
        from: Int,
        to: Int,
    ): ByteArray = array.copyOfRange(from, to)

    fun clear() {
        size = 0
    }

    private fun ensure(capacity: Int) {
        if (capacity <= array.size) return
        var n = array.size * 2
        while (n < capacity) n *= 2
        array = array.copyOf(n)
    }
}
