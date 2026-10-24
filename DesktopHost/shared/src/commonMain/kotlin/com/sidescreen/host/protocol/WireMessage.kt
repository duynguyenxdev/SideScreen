package com.sidescreen.host.protocol

/**
 * Message type bytes of the SideScreen stream protocol. Byte-for-byte
 * compatible with the original macOS host and the Android client.
 */
object WireMessage {
    const val LEGACY_VIDEO_FRAME: Int = 0
    const val DISPLAY_CONFIG: Int = 1
    const val TOUCH_EVENT: Int = 2
    const val PING: Int = 4
    const val PONG: Int = 5
    const val VIDEO_FRAME_WITH_METADATA: Int = 6
    const val KEYFRAME_REQUEST: Int = 7
    const val CLIENT_SUPPORTS_FRAME_METADATA: Int = 8

    /** Client→server, payload-free: "this device has no HEVC decoder". */
    const val CLIENT_AVC_ONLY: Int = 9

    /**
     * Server→client, 1-byte payload ([StreamCodec.wireId]). Sent ONLY to
     * clients that sent [CLIENT_AVC_ONLY]: old clients disconnect on unknown
     * message types, so this must never be sent unsolicited.
     */
    const val CODEC_SELECTED: Int = 10

    /** Client→server, 4-byte payload in [DecoderLimitWire] encoding (issue #41). */
    const val CLIENT_DECODER_LIMITS: Int = 11

    /** Client→server, payload-free: "I read displayConfig as the encoded stream size". */
    const val CLIENT_SUPPORTS_DESKTOP_GEOMETRY: Int = 12

    /** Server→client, 8-byte payload: logical desktop size, display only. Opt-in via type 12. */
    const val DESKTOP_GEOMETRY: Int = 13

    /** Client→server, 12-byte payload: decode limit per [CodecLimits.TABLE_FPS] rate (issue #78). */
    const val CLIENT_DECODER_LIMIT_TABLE: Int = 14
}

/** Video codec used for the encode session and wire stream. */
enum class StreamCodec(val wireId: Int) {
    HEVC(0),
    H264(1),
}

data class Size(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"
}
