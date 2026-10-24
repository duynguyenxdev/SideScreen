package com.sidescreen.host.protocol

/** Encoders for host→client messages. Integers are big-endian unless noted. */
object HostMessages {
    /**
     * Display config: the ENCODED stream size plus a transform packed as
     * rotation + 1000 (flip horizontal) + 2000 (flip vertical).
     */
    fun displayConfig(
        encoded: Size,
        rotation: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
    ): ByteArray {
        val transform = rotation + (if (flipHorizontal) 1000 else 0) + (if (flipVertical) 2000 else 0)
        return ByteArray(13).also {
            it[0] = WireMessage.DISPLAY_CONFIG.toByte()
            putIntBe(it, 1, encoded.width)
            putIntBe(it, 5, encoded.height)
            putIntBe(it, 9, transform)
        }
    }

    fun desktopGeometry(desktop: Size): ByteArray =
        ByteArray(9).also {
            it[0] = WireMessage.DESKTOP_GEOMETRY.toByte()
            putIntBe(it, 1, desktop.width)
            putIntBe(it, 5, desktop.height)
        }

    fun codecSelected(codec: StreamCodec): ByteArray = byteArrayOf(WireMessage.CODEC_SELECTED.toByte(), codec.wireId.toByte())

    fun pong(clientTimestamp: ByteArray): ByteArray = byteArrayOf(WireMessage.PONG.toByte()) + clientTimestamp

    /**
     * Video frame header. With metadata (type 6):
     * [type 1][size i32][keyframe 1][capture timestamp u64]; legacy (type 0):
     * [type 1][size i32]. The Annex-B payload follows the header.
     */
    fun frameHeader(
        payloadSize: Int,
        isKeyframe: Boolean,
        timestampNs: Long,
        withMetadata: Boolean,
    ): ByteArray {
        if (!withMetadata) {
            return ByteArray(5).also {
                it[0] = WireMessage.LEGACY_VIDEO_FRAME.toByte()
                putIntBe(it, 1, payloadSize)
            }
        }
        return ByteArray(14).also {
            it[0] = WireMessage.VIDEO_FRAME_WITH_METADATA.toByte()
            putIntBe(it, 1, payloadSize)
            it[5] = if (isKeyframe) 1 else 0
            putLongBe(it, 6, timestampNs)
        }
    }

    private fun putIntBe(
        b: ByteArray,
        at: Int,
        v: Int,
    ) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun putLongBe(
        b: ByteArray,
        at: Int,
        v: Long,
    ) {
        putIntBe(b, at, (v ushr 32).toInt())
        putIntBe(b, at + 4, v.toInt())
    }
}
