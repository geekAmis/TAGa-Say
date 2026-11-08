package com.tagasay.app.data.network

import com.tagasay.app.data.model.Attachment
import com.tagasay.app.data.model.Poll
import com.tagasay.app.data.model.PollOption
import com.tagasay.app.data.model.ScanVerdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Открытый текст, который уходит внутрь libsignal-конверта.
 *
 * Один формат для всех типов событий: сообщения, вложения, редактирование,
 * удаление, статусы доставки/прочтения. Поле [id] — идентификатор сообщения,
 * он же используется в квитанциях; поэтому отправитель и получатель видят
 * одинаковые id и могут ссылаться на них.
 */
@Serializable
data class WireMessage(
    val v: Int = 1,
    val type: String,
    val id: String = "",
    val timestamp: Long = 0,
    val text: String = "",
    val kind: String = "TEXT",
    val sticker: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val forwardedFrom: String? = null,
    val attachment: WireAttachment? = null,
    val poll: WirePoll? = null,
    val ids: List<String> = emptyList(),
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_MEDIA = "media"
        const val TYPE_EDIT = "edit"
        const val TYPE_DELETE = "delete"
        const val TYPE_DELIVERED = "delivered"
        const val TYPE_READ = "read"
        const val TYPE_REACTION = "reaction"
    }
}

@Serializable
data class WireAttachment(
    val kind: String,
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String,
    val scanVerdict: String = ScanVerdict.SAFE.name,
    val mediaId: String,
    val keyBase64: String,
)

@Serializable
data class WirePollOption(
    val id: String,
    val text: String,
)

@Serializable
data class WirePoll(
    val question: String,
    val options: List<WirePollOption>,
    val multiple: Boolean = false,
    val anonymous: Boolean = true,
)

fun WireAttachment.toAttachment(): Attachment = Attachment(
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    scanVerdict = runCatching { ScanVerdict.valueOf(scanVerdict) }
        .getOrDefault(ScanVerdict.WARNING),
    mediaId = mediaId,
    keyBase64 = keyBase64,
)

fun Poll.toWire(): WirePoll = WirePoll(
    question = question,
    options = options.map { WirePollOption(it.id, it.text) },
    multiple = multiple,
    anonymous = anonymous,
)

fun WirePoll.toPoll(): Poll = Poll(
    question = question,
    options = options.map { PollOption(it.id, it.text) },
    multiple = multiple,
    anonymous = anonymous,
)

/**
 * Бинарный префикс отделяет структурированные сообщения от сырого текста,
 * который отправляют старые клиенты и e2e-peer. Если префикса нет — payload
 * считается обычным текстом.
 */
object WireCodec {
    private val PREFIX = "TAGA-WIRE\u0001".toByteArray()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: WireMessage): ByteArray =
        PREFIX + json.encodeToString(WireMessage.serializer(), message).encodeToByteArray()

    fun decode(payload: ByteArray): WireMessage? {
        if (payload.size <= PREFIX.size) return null
        for (index in PREFIX.indices) {
            if (payload[index] != PREFIX[index]) return null
        }
        return runCatching {
            json.decodeFromString(
                WireMessage.serializer(),
                payload.copyOfRange(PREFIX.size, payload.size).decodeToString(),
            )
        }.getOrNull()
    }
}
