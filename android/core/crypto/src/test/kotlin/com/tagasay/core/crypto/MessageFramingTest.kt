package com.tagasay.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFramingTest {

    @Test
    fun padAndUnpadRoundTrip() {
        val payloads = listOf(
            ByteArray(0),
            "привет".toByteArray(),
            ByteArray(200) { it.toByte() },
            ByteArray(4096) { (it % 251).toByte() },
            ByteArray(70_000) { (it % 127).toByte() },
        )
        for (payload in payloads) {
            val framed = MessageFraming.pad(payload)
            assertTrue("framed must be at least header+payload", framed.size >= payload.size + 8)
            assertArrayEquals(payload, MessageFraming.unpad(framed))
            assertEquals(payload.size, MessageFraming.framedLength(framed))
        }
    }

    @Test
    fun smallMessagesUseSmallestFittingClass() {
        val framed = MessageFraming.pad(ByteArray(10))
        assertEquals(256, framed.size)
        assertEquals(10, MessageFraming.framedLength(framed))
    }

    @Test
    fun classBoundariesAreRespected() {
        // 256 - 8 = 248 bytes payload fit exactly in the 256-byte class.
        assertEquals(256, MessageFraming.pad(ByteArray(240)).size)
        // One more byte pushes the frame into the next class.
        assertEquals(1024, MessageFraming.pad(ByteArray(249)).size)
    }

    @Test
    fun rawLegacyPayloadIsReturnedUnchanged() {
        val raw = "сырой текст без кадрирования".toByteArray()
        assertArrayEquals(raw, MessageFraming.unpad(raw))
        assertNull(MessageFraming.framedLength(raw))
    }

    @Test
    fun malformedFrameLengthIsRejected() {
        val framed = MessageFraming.pad(ByteArray(32))
        // Corrupt the declared length to point past the buffer.
        framed[4] = 0x7f
        assertArrayEquals(framed, MessageFraming.unpad(framed))
        assertNull(MessageFraming.framedLength(framed))
    }

    @Test
    fun sizeClassMatchesServerMapping() {
        assertEquals(0, MessageFraming.sizeClassFor(256))
        assertEquals(1, MessageFraming.sizeClassFor(257))
        assertEquals(1, MessageFraming.sizeClassFor(1024))
        assertEquals(2, MessageFraming.sizeClassFor(4096))
        assertEquals(7, MessageFraming.sizeClassFor(4 * 1024 * 1024))
    }
}
