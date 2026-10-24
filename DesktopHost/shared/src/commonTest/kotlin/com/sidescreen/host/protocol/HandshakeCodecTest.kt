package com.sidescreen.host.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeCodecTest {
    private fun request(
        magic: ByteArray = HandshakeCodec.REQUEST_MAGIC,
        field: ByteArray = ByteArray(32) { it.toByte() },
        name: ByteArray = "iPad Air".encodeToByteArray(),
        nameLen: Int = name.size,
    ) = magic + field + byteArrayOf(nameLen.toByte()) + name

    @Test fun parseValidRequest() {
        val r = HandshakeCodec.parseRequest(request())
        assertEquals(32, r.token.size)
        assertEquals(0x00, r.token.first().toInt())
        assertEquals(0x1F, r.token.last().toInt())
        assertEquals("iPad Air", r.deviceName)
    }

    @Test fun rejectsBadMagic() {
        val e = assertFailsWith<HandshakeException> { HandshakeCodec.parseRequest(request(magic = "XXXX".encodeToByteArray())) }
        assertEquals(HandshakeError.INVALID_MAGIC, e.error)
    }

    @Test fun rejectsZeroNameLength() {
        val e = assertFailsWith<HandshakeException> { HandshakeCodec.parseRequest(request(name = ByteArray(0))) }
        assertEquals(HandshakeError.INVALID_NAME, e.error)
    }

    @Test fun rejectsNameLengthGreaterThan64() {
        val e = assertFailsWith<HandshakeException> { HandshakeCodec.parseRequest(request(name = ByteArray(65) { 0x41 })) }
        assertEquals(HandshakeError.INVALID_NAME, e.error)
    }

    @Test fun rejectsInvalidUtf8Name() {
        val e = assertFailsWith<HandshakeException> { HandshakeCodec.parseRequest(request(name = byteArrayOf(0xC3.toByte()))) }
        assertEquals(HandshakeError.INVALID_NAME, e.error)
    }

    @Test fun encodesResponses() {
        assertContentEquals(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x00), HandshakeCodec.encodeResponse(HandshakeStatus.OK))
        assertContentEquals(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x01), HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_TOKEN))
        assertContentEquals(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x03), HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_NAME))
    }

    @Test fun parsesPairingRequestCodeField() {
        val field = "47293185".encodeToByteArray() + ByteArray(24)
        val r = HandshakeCodec.parsePairingRequest(request(magic = HandshakeCodec.PAIRING_REQUEST_MAGIC, field = field, name = "Boox".encodeToByteArray()))
        assertEquals("47293185", r.code)
        assertEquals("Boox", r.deviceName)
    }

    @Test fun pairingResponseCarriesTokenAndHostName() {
        val token = ByteArray(32) { 7 }
        val r = HandshakeCodec.encodePairingResponse(PairingStatus.OK, token, "Studio")
        assertContentEquals(HandshakeCodec.PAIRING_RESPONSE_MAGIC, r.copyOfRange(0, 4))
        assertEquals(0, r[4].toInt())
        assertContentEquals(token, r.copyOfRange(5, 37))
        assertEquals(6, r[37].toInt())
        assertEquals("Studio", r.copyOfRange(38, r.size).decodeToString())
        assertEquals(5, HandshakeCodec.encodePairingResponse(PairingStatus.INVALID_CODE).size)
    }

    @Test fun hostNameIsCutOnACodePointBoundary() {
        val name = "é".repeat(40) // 80 bytes
        val bytes = HandshakeCodec.utf8Prefix(name, 64)
        assertEquals(64, bytes.size)
        assertEquals("é".repeat(32), bytes.decodeToString())
    }

    @Test fun pairingCodeValidation() {
        assertTrue(PairingCode.validate("4729-3185", "47293185"))
        assertTrue(PairingCode.validate("4729 3185", "47293185"))
        assertFalse(PairingCode.validate("47293186", "47293185"))
        assertFalse(PairingCode.validate("", ""))
        assertEquals("4729-3185", PairingCode.display("47293185"))
        repeat(50) {
            val c = PairingCode.generate()
            assertEquals(8, c.length)
            assertTrue(c.all { it in '0'..'9' })
        }
    }

    @Test fun authTokenCompare() {
        val a = ByteArray(32) { it.toByte() }
        assertTrue(AuthToken.validate(a.copyOf(), a))
        assertFalse(AuthToken.validate(a.copyOf().also { it[31] = 0 }, a))
        assertFalse(AuthToken.validate(ByteArray(31), a))
        assertEquals(32, AuthToken.generate().size)
    }

    @Test fun pairingUrlFields() {
        val url = PairingUrl.build("192.168.1.42", 8888, ByteArray(32) { 0xAB.toByte() }, "Dat's PC+1 é")
        assertTrue(url.startsWith("sidescreen://192.168.1.42:8888?t="))
        val t = url.substringAfter("t=").substringBefore('&')
        assertEquals(43, t.length)
        assertFalse(t.contains('=') || t.contains('+') || t.contains('/'))
        assertTrue(url.endsWith("name=Dat's%20PC%2B1%20%C3%A9"))
    }
}
