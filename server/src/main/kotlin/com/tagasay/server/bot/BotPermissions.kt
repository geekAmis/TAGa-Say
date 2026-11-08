package com.tagasay.server.bot

/**
 * Матрица «метод × scope». Проверка выполняется на сервере для каждого
 * вызова Bot API; клиент/бот не являются источником истины.
 */
object BotPermissions {

    private val METHOD_SCOPES = mapOf(
        "getMe" to null,
        "getUpdates" to BotScopes.UPDATES_READ,
        "getMyCommands" to null,
        "setMyCommands" to null,
        "deleteMyCommands" to null,
        "sendMessage" to BotScopes.MESSAGES_SEND,
        "editMessageText" to BotScopes.MESSAGES_EDIT,
        "deleteMessage" to BotScopes.MESSAGES_DELETE,
        "answerCallbackQuery" to null,
        "getFile" to BotScopes.FILES_READ,
    )

    /** Возвращает требуемый scope или null, если метод доступен любому активному боту. */
    fun requiredScope(method: String): String? = METHOD_SCOPES[method]

    fun allowed(bot: Bot, method: String): Boolean {
        val scope = requiredScope(method) ?: return true
        return bot.scopes.contains(scope)
    }

    /**
     * В 1.2.0 комнаты с ботами не включены: бот не может писать в g:/c:,
     * пока его явно не добавили в комнату (stage 5). Возвращает null, если
     * чат допустим, иначе текст ошибки.
     */
    fun chatDenied(bot: Bot, chatId: String): String? = when {
        chatId.startsWith("u:") -> null
        chatId.startsWith("g:") || chatId.startsWith("c:") ->
            "bot rooms are not enabled in this release"
        else -> "unsupported chat_id"
    }
}
