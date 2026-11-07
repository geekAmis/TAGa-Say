package com.tagasay.app.data.network

import com.tagasay.app.data.model.MessageKind
import com.tagasay.app.data.model.ScanVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WireMessageTest {

    @Test
    fun textRoundTrip() {
        val original = WireMessage(
            type = WireMessage.TYPE_TEXT,
            id = "msg-1",
            timestamp = 1_760_000_000_000,
            text = "привет",
            kind = MessageKind.TEXT.name,
            replyToId = "msg-0",
            replyToText = "цитата",
            forwardedFrom = "чат",
        )
        val decoded = WireCodec.decode(WireCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun mediaRoundTrip() {
        val original = WireMessage(
            type = WireMessage.TYPE_MEDIA,
            id = "msg-2",
            text = "подпись",
            kind = MessageKind.IMAGE.name,
            attachment = WireAttachment(
                kind = MessageKind.IMAGE.name,
                name = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1234,
                sha256 = "abc",
                scanVerdict = ScanVerdict.SAFE.name,
                mediaId = "blob",
                keyBase64 = "key",
            ),
        )
        val decoded = WireCodec.decode(WireCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun receiptRoundTrip() {
        val original = WireMessage(
            type = WireMessage.TYPE_READ,
            id = "receipt",
            ids = listOf("a", "b", "c"),
        )
        assertEquals(original, WireCodec.decode(WireCodec.encode(original)))
    }

    @Test
    fun foreignPayloadIsRejected() {
        assertNull(WireCodec.decode("обычный текст".toByteArray()))
        assertNull(WireCodec.decode(ByteArray(0)))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val prefix = "TAGA-WIRE\u0001".toByteArray()
        val json = """{"v":1,"type":"text","id":"x","text":"hi","futureField":42}"""
        val decoded = WireCodec.decode(prefix + json.toByteArray())
        assertNotNull(decoded)
        assertEquals("x", decoded?.id)
        assertEquals("hi", decoded?.text)
    }
}
