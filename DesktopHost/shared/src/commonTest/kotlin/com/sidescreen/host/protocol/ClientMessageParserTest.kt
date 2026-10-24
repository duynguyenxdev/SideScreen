package com.sidescreen.host.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientMessageParserTest {
    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun f(v: Float) = le(v.toRawBits())

    /** Matches StreamClient.sendTouch on Android: LITTLE_ENDIAN floats and action. */
    private fun touch(
        action: Int,
        vararg pts: Float,
    ): ByteArray {
        var b = byteArrayOf(WireMessage.TOUCH_EVENT.toByte(), (pts.size / 2).toByte())
        pts.forEach { b += f(it) }
        return b + le(action)
    }

    @Test fun parsesOneAndTwoFingerTouches() {
        val p = ClientMessageParser()
        val msgs = p.feed(touch(0, 0.25f, 0.75f) + touch(1, 0.1f, 0.2f, 0.3f, 0.4f))
        assertEquals(ClientMessage.Touch(0.25f, 0.75f, 0, 1), msgs[0])
        assertEquals(ClientMessage.Touch(0.1f, 0.2f, 1, 2, 0.3f, 0.4f), msgs[1])
    }

    @Test fun reassemblesMessagesSplitAcrossReads() {
        val p = ClientMessageParser()
        val all = touch(2, 0.5f, 0.5f) + byteArrayOf(WireMessage.PING.toByte()) + ByteArray(8) { it.toByte() } + byteArrayOf(7, 1)
        val out = all.toList().chunked(3).flatMap { p.feed(it.toByteArray()) }
        assertEquals(3, out.size)
        assertIs<ClientMessage.Touch>(out[0])
        assertEquals(7, (out[1] as ClientMessage.Ping).timestamp[7].toInt())
        assertEquals(ClientMessage.KeyframeRequest(true), out[2])
    }

    @Test fun capabilityHandshakeInClientOrder() {
        // StreamClient sends 9, 11, 14, 12, 8 in that order.
        val p = ClientMessageParser()
        val bytes =
            byteArrayOf(9, 11) + DecoderLimitWire.encodeSize(Size(2800, 1840)) +
                byteArrayOf(14) + DecoderLimitWire.encodeSize(Size(2800, 1840)) + DecoderLimitWire.encodeSize(Size(2304, 1440)) +
                DecoderLimitWire.encodeSize(Size(1616, 1008)) + byteArrayOf(12, 8)
        val out = p.feed(bytes)
        assertEquals(ClientMessage.AvcOnly, out[0])
        assertEquals(ClientMessage.DecoderLimits(Size(2800, 1840)), out[1])
        assertEquals(mapOf(30 to Size(2800, 1840), 60 to Size(2304, 1440), 120 to Size(1616, 1008)), (out[2] as ClientMessage.DecoderLimitTable).table)
        assertEquals(ClientMessage.SupportsDesktopGeometry, out[3])
        assertEquals(ClientMessage.SupportsFrameMetadata, out[4])
    }

    @Test fun skipsUnknownTypesAndNonsenseLimits() {
        val p = ClientMessageParser()
        val out = p.feed(byteArrayOf(99, 11) + DecoderLimitWire.encodeSize(Size(100, 100)) + byteArrayOf(2, 3, 8))
        // 99 skipped, tiny limit ignored, touch with pointerCount 3 skipped byte-wise, then 3 skipped, then 8.
        assertEquals(listOf<ClientMessage>(ClientMessage.SupportsFrameMetadata), out)
    }

    @Test fun malformedLimitPayloadIsConsumedAndIgnored() {
        val p = ClientMessageParser()
        val out = p.feed(byteArrayOf(11, 0x01, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 8))
        assertTrue(out.single() is ClientMessage.SupportsFrameMetadata)
    }
}
