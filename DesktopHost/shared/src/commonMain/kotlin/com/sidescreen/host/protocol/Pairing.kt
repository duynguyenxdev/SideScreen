package com.sidescreen.host.protocol

import com.sidescreen.host.platform.secureRandomBytes
import kotlin.io.encoding.Base64

/** 32-byte wireless auth token: generated locally, embedded in the pairing QR. */
object AuthToken {
    const val LENGTH = 32

    fun generate(): ByteArray = secureRandomBytes(LENGTH)

    /** Constant-time comparison. */
    fun validate(
        candidate: ByteArray,
        expected: ByteArray,
    ): Boolean {
        if (candidate.size != expected.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (candidate[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}

/**
 * Short one-time pairing code for tablets without a camera (issue #35): an
 * alternative front door to the same 32-byte token the QR embeds. A fresh
 * code is generated every time the server starts in wireless mode, after
 * every successful pairing (one-time use), and after too many failed attempts.
 * It never persists.
 */
object PairingCode {
    /** 8 decimal digits: easy to type anywhere; 10^8 is plenty against a 5-attempt limit. */
    const val LENGTH = 8

    fun generate(): String {
        val sb = StringBuilder()
        while (sb.length < LENGTH) {
            for (b in secureRandomBytes(LENGTH)) {
                val v = b.toInt() and 0xFF
                // Rejection-sample so every digit is uniform (256 % 10 != 0).
                if (v < 250 && sb.length < LENGTH) sb.append('0' + v % 10)
            }
        }
        return sb.toString()
    }

    /** "47293185" -> "4729-3185" for display next to the QR. */
    fun display(code: String): String =
        if (code.length != LENGTH) code else code.substring(0, LENGTH / 2) + "-" + code.substring(LENGTH / 2)

    /** Constant-time compare; ignores separators the user might type ("4729-3185", "4729 3185"). */
    fun validate(
        candidate: String,
        expected: String,
    ): Boolean {
        val a = normalize(candidate).encodeToByteArray()
        val b = normalize(expected).encodeToByteArray()
        if (a.size != b.size || b.isEmpty()) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun normalize(raw: String): String = raw.filter { it in '0'..'9' }
}

/** `sidescreen://host:port?t=<base64url token>&name=<percent-encoded name>` — what the QR carries. */
object PairingUrl {
    fun build(
        host: String,
        port: Int,
        token: ByteArray,
        name: String,
    ): String = "sidescreen://$host:$port?t=${base64UrlEncode(token)}&name=${encodeName(name)}"

    fun base64UrlEncode(data: ByteArray): String = Base64.UrlSafe.encode(data).trimEnd('=')

    /**
     * Mirrors Foundation's `urlQueryAllowed` minus `&=?#`: unreserved
     * characters and the remaining sub-delims stay literal (except `+`, which
     * Android's Uri.getQueryParameter would decode as a space), everything else
     * (including every non-ASCII byte) is percent-encoded.
     */
    internal fun encodeName(name: String): String {
        val allowed = "-._~!$'()*,;:@/"
        val sb = StringBuilder()
        for (b in name.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in allowed)) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
