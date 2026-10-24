package com.sidescreen.host.protocol

enum class HandshakeError { INVALID_MAGIC, INVALID_NAME, TRUNCATED }

class HandshakeException(val error: HandshakeError) : Exception(error.name)

enum class HandshakeStatus(val code: Int) {
    OK(0x00),
    INVALID_TOKEN(0x01),
    INVALID_MAGIC(0x02),
    INVALID_NAME(0x03),
}

/** Status byte of the code-pairing response (issue #35). */
enum class PairingStatus(val code: Int) {
    OK(0x00),
    INVALID_CODE(0x01),
}

class ParsedHandshake(val token: ByteArray, val deviceName: String)

class ParsedPairingRequest(val code: String, val deviceName: String)

/**
 * Wireless auth ("SSWA"/"SSWR") and code-pairing ("SSPC"/"SSPR") handshakes.
 *
 * Auth request:     [magic 4][token 32][name_len 1][name N]
 * Auth response:    [magic 4][status 1]
 * Pairing request:  [magic 4][code 8 ASCII digits + zero-pad 24][name_len 1][name N]
 * Pairing response: [magic 4][status 1] then, when ok: [token 32][host_name_len 1][host_name M]
 *
 * The pairing request deliberately mirrors the auth request's 37-byte fixed
 * prefix so an old host answers invalidMagic instead of stalling.
 */
object HandshakeCodec {
    val REQUEST_MAGIC = byteArrayOf(0x53, 0x53, 0x57, 0x41) // "SSWA"
    val RESPONSE_MAGIC = byteArrayOf(0x53, 0x53, 0x57, 0x52) // "SSWR"
    val PAIRING_REQUEST_MAGIC = byteArrayOf(0x53, 0x53, 0x50, 0x43) // "SSPC"
    val PAIRING_RESPONSE_MAGIC = byteArrayOf(0x53, 0x53, 0x50, 0x52) // "SSPR"

    const val TOKEN_LENGTH = 32
    const val PAIRING_CODE_FIELD_LENGTH = 32
    const val FIXED_PREFIX_LENGTH = 4 + 32 + 1
    const val MAX_NAME_LENGTH = 64

    fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= 4 && (0 until 4).all { bytes[it] == magic[it] }

    /** Name length declared by a 37-byte prefix, or null when outside 1..64. */
    fun declaredNameLength(prefix: ByteArray): Int? {
        val len = prefix[36].toInt() and 0xFF
        return len.takeIf { it in 1..MAX_NAME_LENGTH }
    }

    private fun parseNameSuffix(bytes: ByteArray): String {
        val nameLen = declaredNameLength(bytes) ?: throw HandshakeException(HandshakeError.INVALID_NAME)
        if (bytes.size < FIXED_PREFIX_LENGTH + nameLen) throw HandshakeException(HandshakeError.TRUNCATED)
        val name =
            decodeUtf8Strict(bytes.copyOfRange(FIXED_PREFIX_LENGTH, FIXED_PREFIX_LENGTH + nameLen))
                ?: throw HandshakeException(HandshakeError.INVALID_NAME)
        if (name.isEmpty()) throw HandshakeException(HandshakeError.INVALID_NAME)
        return name
    }

    fun parseRequest(bytes: ByteArray): ParsedHandshake {
        if (bytes.size < FIXED_PREFIX_LENGTH) throw HandshakeException(HandshakeError.TRUNCATED)
        if (!hasMagic(bytes, REQUEST_MAGIC)) throw HandshakeException(HandshakeError.INVALID_MAGIC)
        val token = bytes.copyOfRange(4, 4 + TOKEN_LENGTH)
        return ParsedHandshake(token, parseNameSuffix(bytes))
    }

    fun encodeResponse(status: HandshakeStatus): ByteArray = RESPONSE_MAGIC + byteArrayOf(status.code.toByte())

    /** The code is the leading run of non-zero bytes in the 32-byte field. */
    fun parsePairingRequest(bytes: ByteArray): ParsedPairingRequest {
        if (bytes.size < FIXED_PREFIX_LENGTH) throw HandshakeException(HandshakeError.TRUNCATED)
        if (!hasMagic(bytes, PAIRING_REQUEST_MAGIC)) throw HandshakeException(HandshakeError.INVALID_MAGIC)
        val field = bytes.copyOfRange(4, 4 + PAIRING_CODE_FIELD_LENGTH)
        val end = field.indexOfFirst { it == 0.toByte() }.let { if (it < 0) field.size else it }
        val code =
            decodeUtf8Strict(field.copyOfRange(0, end))?.takeIf { it.isNotEmpty() }
                ?: throw HandshakeException(HandshakeError.INVALID_MAGIC)
        return ParsedPairingRequest(code, parseNameSuffix(bytes))
    }

    fun encodePairingResponse(
        status: PairingStatus,
        token: ByteArray? = null,
        hostName: String? = null,
    ): ByteArray {
        val head = PAIRING_RESPONSE_MAGIC + byteArrayOf(status.code.toByte())
        if (status != PairingStatus.OK || token == null) return head
        require(token.size == TOKEN_LENGTH) { "pairing token must be 32 bytes" }
        var nameBytes = utf8Prefix(hostName ?: "Computer", MAX_NAME_LENGTH)
        if (nameBytes.isEmpty()) nameBytes = "Computer".encodeToByteArray()
        return head + token + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    /** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a code point. */
    internal fun utf8Prefix(
        s: String,
        max: Int,
    ): ByteArray {
        val all = s.encodeToByteArray()
        if (all.size <= max) return all
        var end = max
        // Back off continuation bytes (10xxxxxx) so we never end mid-sequence.
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
        return all.copyOfRange(0, end)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
}
