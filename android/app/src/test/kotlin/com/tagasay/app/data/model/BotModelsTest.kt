package com.tagasay.app.data.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контрактные тесты парсинга бот-моделей: entities, inline-клавиатуры,
 * inbox-события и правила дедупликации. Сеть/Android не нужны.
 */
class BotModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses inbox event with entities and inline keyboard`() {
        val payload = """
            [{
              "event_id": 12,
              "type": "bot_message",
              "chat_id": "u:alice",
              "message": {
                "message_id": "m_1a2b3c",
                "chat_id": "u:alice",
                "bot_username": "my_helper_bot",
                "from": "my_helper_bot",
                "is_bot": true,
                "text": "Готово, <b>друг</b>",
                "entities": [
                  {"type": "bold", "offset": 8, "length": 4},
                  {"type": "code", "offset": 0, "length": 5, "url": ""}
                ],
                "reply_markup": {"inline_keyboard": [[
                  {"text": "Открыть", "url": "https://example.com"},
                  {"text": "Ок", "callback_data": "ack:1"}
                ]]},
                "reply_to": "",
                "date": 1794100001,
                "edited_at": 0,
                "deleted_at": 0
              }
            }]
        """.trimIndent()

        val events = json.decodeFromString(
            ListSerializer(BotInboxEvent.serializer()),
            payload,
        )
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(12L, event.eventId)
        assertEquals(BotInboxEvent.TYPE_MESSAGE, event.type)

        val message = event.message
        assertNotNull(message)
        assertEquals("m_1a2b3c", message!!.messageId)
        assertEquals("my_helper_bot", message.botUsername)
        assertEquals("Готово, <b>друг</b>", message.text)
        assertEquals(2, message.entities.size)
        assertEquals("bold", message.entities[0].type)
        assertEquals(12, message.entities[0].end)

        val rows = message.keyboard
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
        assertTrue(rows[0][0].isUrl)
        assertEquals("https://example.com", rows[0][0].url)
        assertTrue(rows[0][1].isCallback)
        assertEquals("ack:1", rows[0][1].callbackData)

        // date приходит в секундах → миллисекунды.
        assertEquals(1794100001000L, message.timestampMs)
    }

    @Test
    fun `inline keyboard accepts raw array and strips empty rows`() {
        val raw = Json.parseToJsonElement(
            """[
                 [{"text":"A","callback_data":"a"},{"text":""}],
                 [],
                 [{"text":"B","web_app":{"url":"https://wa.example"}}]
               ]""",
        )
        val rows = parseInlineKeyboard(raw)
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].size)
        assertEquals("a", rows[0][0].callbackData)
        assertEquals("https://wa.example", rows[1][0].webApp?.url)
    }

    @Test
    fun `inline keyboard returns empty for null and malformed data`() {
        assertTrue(parseInlineKeyboard(null).isEmpty())
        assertTrue(parseInlineKeyboard(Json.parseToJsonElement("\"nope\"")).isEmpty())
        assertTrue(parseInlineKeyboard(Json.parseToJsonElement("{}")).isEmpty())
    }

    @Test
    fun `entities outside the text are dropped`() {
        val text = "Hello"
        val entities = listOf(
            BotEntity("bold", 0, 5),
            BotEntity("italic", 3, 10), // выходит за границы
            BotEntity("code", -1, 2), // отрицательное смещение
            BotEntity("underline", 4, 0), // пустая длина
            BotEntity("strikethrough", 5, 1), // offset+length > length
        )
        val valid = validBotEntities(entities, text.length)
        assertEquals(1, valid.size)
        assertEquals("bold", valid[0].type)
    }

    @Test
    fun `dedupe rejects duplicates tombstones and blank ids`() {
        val existing = setOf("m_1", "m_2")
        assertFalse(shouldAcceptBotMessage("m_1", 0, existing))
        assertFalse(shouldAcceptBotMessage("m_3", 1794100000, existing)) // deleted_at > 0
        assertFalse(shouldAcceptBotMessage("", 0, existing))
        assertTrue(shouldAcceptBotMessage("m_3", 0, existing))
    }

    @Test
    fun `bot card parses snake_case and keeps local flags`() {
        val payload = """
            {
              "username": "my_helper_bot",
              "display_name": "My Helper",
              "about": "Кратко",
              "description": "Экран до /start",
              "verified": true,
              "builtin": false,
              "status": "active",
              "privacy_mode": true,
              "can_join_groups": false,
              "supports_inline_queries": true,
              "domain": "example.com",
              "created_at": 1794100000000,
              "commands": [{"command":"start","description":"Начать"}],
              "started": true,
              "blocked": false
            }
        """.trimIndent()

        val bot = json.decodeFromString(BotInfo.serializer(), payload)
        assertEquals("my_helper_bot", bot.username)
        assertEquals("My Helper", bot.displayName)
        assertTrue(bot.verified)
        assertFalse(bot.canJoinGroups)
        assertTrue(bot.supportsInlineQueries)
        assertEquals(1, bot.commands.size)
        assertTrue(bot.started)
        assertFalse(bot.deleted)
        assertEquals("My Helper", bot.title)
    }

    @Test
    fun `bot update supports message and callback query`() {
        val payload = """
            {
              "update_id": 43,
              "callback_query": {
                "id": "cq_1a2b3c",
                "from": {"id":"u:alice","username":"alice","display_name":"alice","is_bot":false},
                "chat_instance": "u:alice",
                "data": "ack:1",
                "message": {
                  "message_id": "m_9f1c",
                  "date": 1794100000,
                  "chat": {"id":"u:alice","type":"private","username":"alice"},
                  "from": {"id":"b:my_helper_bot","username":"my_helper_bot","is_bot":true},
                  "text": "Выберите"
                }
              }
            }
        """.trimIndent()

        val update = json.decodeFromString(BotUpdate.serializer(), payload)
        assertEquals(43L, update.updateId)
        val query = update.callbackQuery
        assertNotNull(query)
        assertEquals("ack:1", query!!.data)
        assertEquals("alice", query.from.username)
        assertEquals("m_9f1c", query.message?.messageId)
    }

    @Test
    fun `bot time keeps milliseconds and converts seconds`() {
        assertEquals(1794100001000L, BotTime.toMillis(1794100001))
        assertEquals(1794100001234L, BotTime.toMillis(1794100001234))
    }

    @Test
    fun `message serialization keeps keyboard and entities`() {
        val message = Message(
            id = "m_1",
            chatId = "bot_echo_bot",
            senderId = "echo_bot",
            senderName = "Echo",
            text = "Привет",
            timestamp = 1,
            isMine = false,
            entities = listOf(BotEntity("bold", 0, 6)),
            keyboard = listOf(listOf(InlineKeyboardButton("Ок", callbackData = "ok"))),
        )
        val encoded = json.encodeToString(Message.serializer(), message)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(1, decoded.entities.size)
        assertEquals("ok", decoded.keyboard[0][0].callbackData)
    }
}
