package com.tagasay.server.bot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * @BotFather — встроенный бот платформы (без внешнего токена и webhook).
 * FSM на владельца хранится в BotStore и переживает рестарт; таймаут 20 минут.
 *
 * callback_data: `bf:<action>:<botId|->:<hmac8>`, подпись привязана к
 * владельцу, действие и botId. Данные кнопки не считаются доверенными.
 */
class BotFather(
    private val store: BotStore,
    private val auth: BotAuth,
    private val createBot: (String, String, String) -> BotCreateOutcome,
) {
    fun handle(owner: String, raw: String): List<BotFatherReply> {
        val clean = owner.lowercase()
        val text = raw.trim()
        if (text.isEmpty()) return listOf(reply("Отправьте команду или /help."))
        val firstLine = text.lineSequence().first().trim()
        val command = if (firstLine.startsWith("/")) {
            firstLine.substringBefore(' ').substringBefore('@').lowercase()
        } else {
            ""
        }
        val argument = if (command.isNotEmpty()) {
            text.substringAfter(firstLine, "").trim().ifBlank { firstLine.substringAfter(' ', "").trim() }
        } else {
            ""
        }
        if (command.isNotEmpty()) return handleCommand(clean, command, argument)
        return handleStep(clean, text)
    }

    fun handleCallback(owner: String, data: String): List<BotFatherReply> {
        val clean = owner.lowercase()
        val parsed = parseCallback(clean, data)
            ?: return listOf(reply("Кнопка устарела или повреждена. Откройте /mybots заново."))
        if (parsed.action == "newbot") return startNewBot(clean)
        if (parsed.action == "mybots") return listBots(clean)
        val bot = store.bot(parsed.botId)
            ?: return listOf(reply("Бот не найден."))
        if (!bot.owner.equals(clean, true)) return listOf(reply("Это чужой бот: доступ запрещён."))
        if (bot.status != "active") return listOf(reply("Бот удалён. /mybots"))
        return when (parsed.action) {
            "card" -> card(bot)
            "token" -> showToken(bot)
            "revoke" -> confirmRevoke(bot)
            "revoke_yes" -> performRevoke(clean, bot)
            "delete" -> askDeleteConfirmation(clean, bot)
            "setname" -> setField(clean, bot, "setname", null)
            "setabout" -> setField(clean, bot, "setabout", null)
            "setdescription" -> setField(clean, bot, "setdescription", null)
            "setcommands" -> setField(clean, bot, "setcommands", null)
            "privacy" -> togglePrivacy(clean, bot)
            "joingroups" -> toggleJoinGroups(clean, bot)
            "inline" -> toggleInline(clean, bot)
            else -> listOf(reply("Неизвестное действие. /help"))
        }
    }

    // -------------------------------------------------------------- commands

    private fun handleCommand(owner: String, command: String, argument: String): List<BotFatherReply> =
        when (command) {
            "/start", "/help" -> listOf(
                BotFatherReply(
                    text = """
                        Привет! Я @BotFather — управляю ботами TAGa-Say.
                        Здесь можно создать бота, получить токен, настроить имя, описание, команды,
                        приватность, группы и inline-режим.

                        Команды:
                        /newbot — создать бота
                        /mybots — мои боты
                        /token — показать токен
                        /revoke — отозвать токен и выпустить новый
                        /deletebot — удалить бота
                        /setname /setabouttext /setdescription
                        /setcommands /setprivacy /setjoingroups /setinline
                        /cancel — отменить текущий диалог
                    """.trimIndent(),
                    replyMarkup = keyboard(
                        listOf("Создать бота" to cb(owner, "newbot", "-"), "Мои боты" to cb(owner, "mybots", "-")),
                    ),
                ),
            )
            "/newbot" -> startNewBot(owner)
            "/mybots" -> listBots(owner)
            "/token" -> chooseBot(owner, "token")
            "/revoke" -> chooseBot(owner, "revoke")
            "/deletebot" -> chooseBot(owner, "delete")
            "/setname" -> chooseBot(owner, "setname", argument)
            "/setabouttext" -> chooseBot(owner, "setabout", argument)
            "/setdescription" -> chooseBot(owner, "setdescription", argument)
            "/setcommands" -> chooseBot(owner, "setcommands", argument)
            "/setprivacy" -> chooseBot(owner, "privacy")
            "/setjoingroups" -> chooseBot(owner, "joingroups")
            "/setinline" -> chooseBot(owner, "inline")
            "/cancel" -> {
                store.resetSession(owner)
                listOf(reply("Диалог отменён. /help — список команд."))
            }
            "/setdomain", "/newapp", "/myapps", "/editapp", "/deleteapp", "/setmenubutton",
            "/setuserpic", "/deleteuserpic", "/setinlinegeo", "/authorize",
            -> listOf(reply("Команда $command появится в следующем релизе (Web App / аватар / гео)."))
            else -> listOf(reply("Неизвестная команда. /help — список команд."))
        }

    private fun startNewBot(owner: String): List<BotFatherReply> {
        if (store.countOwned(owner) >= BotService.MAX_BOTS_PER_OWNER) {
            return listOf(reply("Лимит: не больше ${BotService.MAX_BOTS_PER_OWNER} ботов на владельца."))
        }
        store.saveSession(session(owner, step = "await_new_name"))
        return listOf(reply("Как назовём бота? Отправьте имя (1–64 символа).\n/cancel — отмена."))
    }

    private fun listBots(owner: String): List<BotFatherReply> {
        val bots = store.botsOwned(owner)
        if (bots.isEmpty()) {
            return listOf(
                BotFatherReply(
                    text = "У вас пока нет ботов.",
                    replyMarkup = keyboard(listOf("Создать бота" to cb(owner, "newbot", "-"))),
                ),
            )
        }
        val rows = bots.map { bot ->
            listOf("${bot.displayName} (@${bot.username})" to cb(owner, "card", bot.id))
        }
        return listOf(
            BotFatherReply(
                text = "Ваши боты (${bots.size}/${BotService.MAX_BOTS_PER_OWNER}):",
                replyMarkup = keyboardRows(rows),
            ),
        )
    }

    private fun chooseBot(
        owner: String,
        action: String,
        value: String? = null,
    ): List<BotFatherReply> {
        val bots = store.botsOwned(owner)
        if (bots.isEmpty()) {
            return listOf(
                BotFatherReply(
                    text = "У вас пока нет ботов.",
                    replyMarkup = keyboard(listOf("Создать бота" to cb(owner, "newbot", "-"))),
                ),
            )
        }
        if (bots.size == 1) return runAction(owner, action, bots.first(), value)
        if (!value.isNullOrBlank()) {
            val clean = value.removePrefix("@").lowercase()
            val found = bots.firstOrNull { it.username.equals(clean, true) }
            if (found != null) return runAction(owner, action, found, null)
        }
        val rows = bots.map { bot ->
            listOf("${bot.displayName} (@${bot.username})" to cb(owner, action, bot.id))
        }
        return listOf(
            BotFatherReply(
                text = "Выберите бота:",
                replyMarkup = keyboardRows(rows),
            ),
        )
    }

    private fun runAction(owner: String, action: String, bot: Bot, value: String?): List<BotFatherReply> =
        when (action) {
            "token" -> showToken(bot)
            "revoke" -> confirmRevoke(bot)
            "delete" -> askDeleteConfirmation(owner, bot)
            "setname", "setabout", "setdescription", "setcommands" -> setField(owner, bot, action, value)
            "privacy" -> togglePrivacy(owner, bot)
            "joingroups" -> toggleJoinGroups(owner, bot)
            "inline" -> toggleInline(owner, bot)
            "card" -> card(bot)
            else -> listOf(reply("Неизвестное действие."))
        }

    // ----------------------------------------------------------------- cards

    private fun card(bot: Bot): List<BotFatherReply> {
        val status = when (bot.status) {
            "active" -> "активен"
            "deleted" -> "удалён"
            else -> bot.status
        }
        val created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(bot.createdAt))
        val text = buildString {
            appendLine("@${bot.username} — ${bot.displayName}")
            appendLine("Статус: $status")
            if (bot.about.isNotBlank()) appendLine("Кратко: ${bot.about}")
            if (bot.description.isNotBlank()) appendLine("Описание: ${bot.description}")
            appendLine("Приватность: ${if (bot.privacyMode) "вкл" else "выкл"}")
            appendLine("Группы: ${if (bot.canJoinGroups) "разрешены" else "запрещены"}")
            appendLine("Inline: ${if (bot.supportsInlineQueries) "вкл" else "выкл"}")
            appendLine("Команды: ${bot.commands.size}")
            append("Создан: $created UTC")
        }
        return listOf(
            BotFatherReply(
                text = text,
                replyMarkup = keyboardRows(
                    listOf(
                        listOf("Токен" to cb(bot.owner, "token", bot.id)),
                        listOf("Отозвать токен" to cb(bot.owner, "revoke", bot.id)),
                        listOf("Удалить бота" to cb(bot.owner, "delete", bot.id)),
                        listOf(
                            "Имя" to cb(bot.owner, "setname", bot.id),
                            "Команды" to cb(bot.owner, "setcommands", bot.id),
                        ),
                        listOf(
                            "Приватность" to cb(bot.owner, "privacy", bot.id),
                            "Inline" to cb(bot.owner, "inline", bot.id),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun showToken(bot: Bot): List<BotFatherReply> {
        val token = auth.revealToken(bot)
            ?: return listOf(reply("Не удалось расшифровать токен: ключ TAGASAY_BOT_TOKEN_KEY недоступен. Токен можно отозвать и выпустить новый (/revoke)."))
        return listOf(
            reply(
                "⚠️ Токен даёт полный доступ к боту. Никому его не передавайте и не публикуйте.\n\n$token\n\n" +
                    "Если токен утёк — /revoke.",
            ),
        )
    }

    private fun confirmRevoke(bot: Bot): List<BotFatherReply> = listOf(
        BotFatherReply(
            text = "Отозвать текущий токен @${bot.username}? Старый токен перестанет работать сразу.",
            replyMarkup = keyboardRows(
                listOf(
                    listOf("Отозвать" to cb(bot.owner, "revoke_yes", bot.id)),
                    listOf("Отмена" to cb(bot.owner, "card", bot.id)),
                ),
            ),
        ),
    )

    private fun performRevoke(owner: String, bot: Bot): List<BotFatherReply> {
        if (!bot.owner.equals(owner, true)) return listOf(reply("Это чужой бот: доступ запрещён."))
        val token = auth.rotateToken(bot)
        return listOf(reply("Новый токен @${bot.username}:\n\n$token\n\nСтарый токен больше не действует."))
    }

    private fun askDeleteConfirmation(owner: String, bot: Bot): List<BotFatherReply> {
        store.saveSession(session(owner, step = "await_delete_confirm", botId = bot.id))
        return listOf(reply("Чтобы удалить @${bot.username}, отправьте @${bot.username}. /cancel — отмена."))
    }

    private fun deleteBot(owner: String, bot: Bot): List<BotFatherReply> {
        if (!bot.owner.equals(owner, true)) return listOf(reply("Это чужой бот: доступ запрещён."))
        store.deleteBot(bot.id)
        store.resetSession(owner)
        return listOf(reply("@${bot.username} удалён. Отправка сообщений больше невозможна; клиент помечает чат как «бот удалён»."))
    }

    // ------------------------------------------------------------- settings

    private fun setField(owner: String, bot: Bot, action: String, value: String?): List<BotFatherReply> {
        if (!value.isNullOrBlank()) {
            return applyField(owner, bot, action, value)
        }
        val step = when (action) {
            "setname" -> "await_set_name"
            "setabout" -> "await_set_about"
            "setdescription" -> "await_set_description"
            else -> "await_set_commands"
        }
        store.saveSession(session(owner, step = step, botId = bot.id, pendingAction = action))
        val hint = when (action) {
            "setname" -> "Отправьте новое имя (1–64 символа)."
            "setabout" -> "Отправьте краткое описание (0–120 символов)."
            "setdescription" -> "Отправьте описание для экрана до /start (0–512 символов)."
            else -> "Отправьте команды, по одной в строке: command - описание (до ${BotService.MAX_COMMANDS})."
        }
        return listOf(reply(hint))
    }

    private fun applyField(owner: String, bot: Bot, action: String, value: String): List<BotFatherReply> {
        return when (action) {
            "setname" -> {
                val name = value.trim()
                if (name.length !in 1..64) return listOf(reply("Имя должно быть 1–64 символа. Попробуйте ещё раз."))
                store.updateBot(bot.copy(displayName = name))
                store.resetSession(owner)
                listOf(reply("Имя обновлено: $name."))
            }
            "setabout" -> {
                val about = value.trim()
                if (about.length > 120) return listOf(reply("Краткое описание — до 120 символов. Попробуйте ещё раз."))
                store.updateBot(bot.copy(about = about))
                store.resetSession(owner)
                listOf(reply("Краткое описание обновлено."))
            }
            "setdescription" -> {
                val description = value.trim()
                if (description.length > 512) return listOf(reply("Описание — до 512 символов. Попробуйте ещё раз."))
                store.updateBot(bot.copy(description = description))
                store.resetSession(owner)
                listOf(reply("Описание обновлено."))
            }
            else -> applyCommands(owner, bot, value)
        }
    }

    private fun applyCommands(owner: String, bot: Bot, value: String): List<BotFatherReply> {
        val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return keepCommandsStep(owner, bot, "Нужна хотя бы одна команда вида `command - описание`.")
        if (lines.size > BotService.MAX_COMMANDS) {
            return keepCommandsStep(owner, bot, "Слишком много команд: максимум ${BotService.MAX_COMMANDS}.")
        }
        val commands = ArrayList<BotCommand>()
        for (line in lines) {
            val separator = line.indexOf(" - ").takeIf { it > 0 }
                ?: line.indexOf('-').takeIf { it > 0 }
                ?: return keepCommandsStep(owner, bot, "Строка без разделителя: $line")
            val name = line.substring(0, separator).trim().removePrefix("/").lowercase()
            val description = line.substring(separator + 1).trim()
            if (!name.matches(BotService.COMMAND_REGEX)) {
                return keepCommandsStep(owner, bot, "Недопустимая команда: $name")
            }
            if (description.length !in 1..256) {
                return keepCommandsStep(owner, bot, "Описание команды $name — 1–256 символов.")
            }
            commands.add(BotCommand(name, description))
        }
        if (commands.map { it.command }.toSet().size != commands.size) {
            return keepCommandsStep(owner, bot, "Команды повторяются.")
        }
        store.updateBot(bot.copy(commands = commands))
        store.resetSession(owner)
        return listOf(reply("Сохранено команд: ${commands.size}."))
    }

    private fun keepCommandsStep(owner: String, bot: Bot, error: String): List<BotFatherReply> {
        store.saveSession(
            session(owner, step = "await_set_commands", botId = bot.id, pendingAction = "setcommands"),
        )
        return listOf(reply("$error\nОтправьте список ещё раз или /cancel."))
    }

    private fun togglePrivacy(owner: String, bot: Bot): List<BotFatherReply> {
        val next = !bot.privacyMode
        store.updateBot(bot.copy(privacyMode = next))
        val text = if (next) {
            "Приватность включена: в группах бот видит только команды, упоминания и ответы на свои сообщения."
        } else {
            "Приватность выключена: бот будет получать все сообщения группы. Это видно участникам — предупредите их."
        }
        return listOf(reply(text))
    }

    private fun toggleJoinGroups(owner: String, bot: Bot): List<BotFatherReply> {
        val next = !bot.canJoinGroups
        store.updateBot(bot.copy(canJoinGroups = next))
        return listOf(reply(if (next) "Добавление в группы разрешено." else "Добавление в группы запрещено."))
    }

    private fun toggleInline(owner: String, bot: Bot): List<BotFatherReply> {
        val next = !bot.supportsInlineQueries
        val scopes = if (next) (bot.scopes + BotScopes.INLINE).distinct() else bot.scopes - BotScopes.INLINE
        store.updateBot(bot.copy(supportsInlineQueries = next, scopes = scopes))
        if (!next) {
            store.resetSession(owner)
            return listOf(reply("Inline-режим выключен."))
        }
        store.saveSession(session(owner, step = "await_inline_placeholder", botId = bot.id, pendingAction = "inline"))
        return listOf(reply("Inline-режим включён. Отправьте placeholder для поля ввода (1–64 символа)."))
    }

    // ------------------------------------------------------------------- FSM

    private fun handleStep(owner: String, text: String): List<BotFatherReply> {
        val current = store.sessionFor(owner, SESSION_TTL_MS)
            ?: return listOf(reply("Откройте /help и выберите действие."))
        return when (current.step) {
            "await_new_name" -> {
                val name = text.trim()
                if (name.length !in 1..64) {
                    return listOf(reply("Имя должно быть 1–64 символа. Попробуйте ещё раз или /cancel."))
                }
                store.saveSession(current.copy(step = "await_new_username", draftName = name))
                listOf(reply("Теперь username: латиница, цифры и _, 5–32 символа, с суффиксом bot. Например: my_helper_bot."))
            }
            "await_new_username" -> createFromDraft(owner, current.draftName, text.trim())
            "await_set_name" -> withBot(current) { bot -> applyField(owner, bot, "setname", text) }
            "await_set_about" -> withBot(current) { bot -> applyField(owner, bot, "setabout", text) }
            "await_set_description" -> withBot(current) { bot -> applyField(owner, bot, "setdescription", text) }
            "await_set_commands" -> withBot(current) { bot -> applyCommands(owner, bot, text) }
            "await_inline_placeholder" -> withBot(current) { bot ->
                val placeholder = text.trim()
                if (placeholder.length !in 1..64) {
                    return@withBot listOf(reply("Placeholder — 1–64 символа. Попробуйте ещё раз или /cancel."))
                }
                store.updateBot(bot.copy(inlinePlaceholder = placeholder))
                store.resetSession(owner)
                listOf(reply("Placeholder сохранён: $placeholder."))
            }
            "await_delete_confirm" -> withBot(current) { bot ->
                if (!text.removePrefix("@").equals(bot.username, ignoreCase = true)) {
                    return@withBot listOf(reply("Не совпало. Отправьте @${bot.username} для удаления или /cancel."))
                }
                deleteBot(owner, bot)
            }
            else -> {
                store.resetSession(owner)
                listOf(reply("Диалог сброшен. /help — список команд."))
            }
        }
    }

    private fun withBot(
        session: BotFatherSession,
        block: (Bot) -> List<BotFatherReply>,
    ): List<BotFatherReply> {
        val bot = store.bot(session.botId) ?: return listOf(reply("Бот не найден. /cancel."))
        if (!bot.owner.equals(session.owner, true)) return listOf(reply("Это чужой бот: доступ запрещён."))
        return block(bot)
    }

    private fun createFromDraft(owner: String, name: String, username: String): List<BotFatherReply> {
        val outcome = createBot(owner, name, username)
        if (outcome.bot == null || outcome.token == null) {
            store.saveSession(
                store.session(owner)?.copy(step = "await_new_username", draftName = name)
                    ?: session(owner, step = "await_new_username", draftName = name),
            )
            val reason = when {
                outcome.error?.contains("busy") == true -> "Этот username уже занят."
                outcome.error?.contains("reserved") == true -> "Этот username зарезервирован."
                outcome.error?.contains("limit") == true -> "Достигнут лимит ботов."
                else -> outcome.error ?: "Не удалось создать бота."
            }
            return listOf(reply("$reason\nОтправьте другой username или /cancel."))
        }
        store.resetSession(owner)
        val bot = outcome.bot
        return listOf(
            reply(
                "Бот создан: @${bot.username}\n\nТокен (показывается по явному действию, храните как пароль):\n\n" +
                    "${outcome.token}\n\nДальше: /setcommands, /setdescription, /setprivacy.",
            ),
        )
    }

    // ------------------------------------------------------------ keyboard

    private data class ParsedCallback(val action: String, val botId: String)

    private fun parseCallback(owner: String, data: String): ParsedCallback? {
        val parts = data.split(":")
        if (parts.size != 4 || parts[0] != "bf") return null
        val action = parts[1]
        val botId = parts[2]
        val signature = parts[3]
        if (!auth.verifyCallbackSignature(owner, action, botId, signature)) return null
        return ParsedCallback(action, botId)
    }

    private fun cb(owner: String, action: String, botId: String): String {
        val signature = auth.callbackSignature(owner, action, botId)
        return "bf:$action:$botId:$signature".take(64)
    }

    private fun session(
        owner: String,
        step: String = "idle",
        botId: String = "",
        pendingAction: String = "",
        draftName: String = "",
    ) = BotFatherSession(
        owner = owner.lowercase(),
        step = step,
        botId = botId,
        pendingAction = pendingAction,
        draftName = draftName,
        updatedAt = System.currentTimeMillis(),
    )

    private fun keyboard(row: List<Pair<String, String>>): JsonElement =
        keyboardRows(listOf(row))

    private fun keyboardRows(rows: List<List<Pair<String, String>>>): JsonElement =
        buildJsonObject {
            put(
                "inline_keyboard",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonArray {
                                row.forEach { (text, data) ->
                                    add(
                                        buildJsonObject {
                                            put("text", text)
                                            put("callback_data", data)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }

    private fun reply(text: String) = BotFatherReply(text = text)

    companion object {
        const val SESSION_TTL_MS = 20 * 60 * 1000L
    }
}
