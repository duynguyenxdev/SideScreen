package com.sidescreen.host.video

import com.sidescreen.host.protocol.StreamCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnexBFramerTest {
    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    // HEVC NAL headers: type << 1 in the first byte, then layer/tid byte 0x01.
    private fun hevc(
        type: Int,
        firstSlice: Boolean = true,
        payload: Int = 20,
    ): ByteArray {
        val body = ByteArray(payload) { (0x40 + it % 30).toByte() }
        if (type < 32) body[0] = if (firstSlice) 0x80.toByte() else 0x00
        return sc4 + byteArrayOf((type shl 1).toByte(), 0x01) + body
    }

    private fun keyframe() = hevc(32) + hevc(33) + hevc(34) + hevc(19)

    private fun pframe() = hevc(1)

    @Test fun splitsAccessUnitsAtTheNextPicture() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        val k = keyframe()
        val p1 = pframe()
        val out = f.push(k + p1 + pframe())
        assertEquals(2, out.size)
        assertTrue(out[0].isKeyframe)
        assertContentEquals(k, out[0].data)
        assertFalse(out[1].isKeyframe)
        assertContentEquals(p1, out[1].data)
    }

    @Test fun handlesByteAtATimeInputAnd3ByteStartCodes() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        val p = sc3 + byteArrayOf(2, 1, 0x80.toByte(), 5, 6, 7)
        val stream = keyframe() + p + pframe()
        val out = stream.flatMap { f.push(byteArrayOf(it)) }
        assertEquals(2, out.size)
        assertContentEquals(p, out[1].data)
    }

    @Test fun multiSliceFramesStayTogether() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        val frame = hevc(1, firstSlice = true) + hevc(1, firstSlice = false)
        val out = f.push(keyframe() + frame + pframe())
        assertContentEquals(frame, out[1].data)
    }

    @Test fun idleFlushEmitsTheInFlightPicture() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        assertEquals(0, f.push(keyframe()).size)
        val flushed = f.flushIdle()
        assertEquals(1, flushed.size)
        assertTrue(flushed[0].isKeyframe)
        assertEquals(0, f.push(pframe()).size)
        assertEquals(1, f.flushIdle().size)
        assertFalse(f.takeDesync())
    }

    @Test fun idleFlushOnlyCompletesPictureNals() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        f.push(hevc(32) + hevc(33))
        assertTrue(f.flushIdle().isEmpty())
    }

    @Test fun prematureIdleFlushIsDetected() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        val k = keyframe()
        f.push(k.copyOfRange(0, k.size - 5))
        assertEquals(1, f.flushIdle().size)
        f.push(k.copyOfRange(k.size - 5, k.size) + pframe() + pframe())
        assertTrue(f.takeDesync())
        assertFalse(f.takeDesync())
    }

    @Test fun keyframesWithoutParameterSetsGetTheCachedOnes() {
        val f = AnnexBFramer(StreamCodec.HEVC)
        val bareIdr = hevc(19)
        val out = f.push(keyframe() + pframe() + bareIdr + pframe())
        val idr = out[2]
        assertTrue(idr.isKeyframe)
        assertContentEquals(hevc(32) + hevc(33) + hevc(34) + bareIdr, idr.data)
    }

    @Test fun h264FramesSplitOnFirstMbAndAud() {
        val f = AnnexBFramer(StreamCodec.H264)
        fun nal(header: Int, first: Boolean = true) = sc4 + byteArrayOf(header.toByte(), if (first) 0x88.toByte() else 0x08, 1, 2, 3)
        val k = nal(0x09) + nal(0x67) + nal(0x68) + nal(0x65)
        val p = nal(0x09) + nal(0x41)
        val out = f.push(k + p + nal(0x09))
        assertEquals(2, out.size)
        assertTrue(out[0].isKeyframe)
        assertContentEquals(k, out[0].data)
        assertContentEquals(p, out[1].data)
    }
}
