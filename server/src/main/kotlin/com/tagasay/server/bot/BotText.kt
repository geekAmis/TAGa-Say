package com.tagasay.server.bot

/**
 * Разбор `parse_mode` на сервере (ТЗ §5.4): храним plain text + entities,
 * смещения — в UTF-16 (как индексы Kotlin/Java String). Неизвестные теги,
 * незакрытые/несовпадающие теги и >100 entities отклоняются 400.
 */
internal object BotText {

    data class Parsed(val text: String, val entities: List<BotEntity>)

    private val htmlTags = mapOf(
        "b" to "bold",
        "strong" to "bold",
        "i" to "italic",
        "em" to "italic",
        "u" to "underline",
        "s" to "strikethrough",
        "strike" to "strikethrough",
        "del" to "strikethrough",
        "code" to "code",
        "pre" to "pre",
        "a" to "text_link",
    )

    private val entityTypes = setOf(
        "bold", "italic", "underline", "strikethrough", "code", "pre",
        "text_link", "text_mention", "bot_command",
    )

    fun parse(raw: String, mode: String?): Parsed = when (mode?.uppercase()) {
        null, "" -> Parsed(raw, emptyList())
        "HTML" -> parseHtml(raw)
        "MARKDOWNV2", "MARKDOWN" -> parseMarkdown(raw)
        else -> throw BotApiException(400, "unsupported parse_mode")
    }

    fun validateEntities(text: String, entities: List<BotEntity>): List<BotEntity> {
        if (entities.size > 100) throw BotApiException(400, "too many entities")
        return entities.map { entity ->
            if (entity.type !in entityTypes) throw BotApiException(400, "unsupported entity type")
            if (entity.offset < 0 || entity.length <= 0 || entity.offset + entity.length > text.length) {
                throw BotApiException(400, "invalid entity range")
            }
            if (entity.type == "text_link") validateUrl(entity.url)
            entity
        }
    }

    private data class OpenTag(val name: String, val type: String, val start: Int, val url: String)

    private fun parseHtml(raw: String): Parsed {
        val out = StringBuilder()
        val entities = ArrayList<BotEntity>()
        val stack = ArrayDeque<OpenTag>()
        var index = 0
        while (index < raw.length) {
            val lt = raw.indexOf('<', index)
            if (lt < 0) {
                out.append(decode(raw.substring(index)))
                break
            }
            out.append(decode(raw.substring(index, lt)))
            val gt = raw.indexOf('>', lt)
            if (gt < 0) throw BotApiException(400, "unclosed HTML tag")
            val tag = raw.substring(lt + 1, gt).trim()
            if (tag.startsWith("/")) {
                val name = tag.substring(1).trim().lowercase()
                val open = stack.removeLastOrNull()
                    ?: throw BotApiException(400, "unexpected closing tag")
                if (open.name != name) throw BotApiException(400, "mismatched closing tag")
                val length = out.length - open.start
                if (length > 0) entities.add(BotEntity(open.type, open.start, length, open.url))
            } else {
                if (tag.isEmpty() || tag.startsWith("!")) throw BotApiException(400, "unsupported HTML tag")
                val name = tag.substringBefore(' ').substringBefore('\t').lowercase()
                val type = htmlTags[name] ?: throw BotApiException(400, "unsupported HTML tag")
                val url = if (name == "a") {
                    val href = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
                        .find(tag)
                        ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                        ?: throw BotApiException(400, "link without href")
                    validateUrl(href)
                    href
                } else {
                    ""
                }
                stack.addLast(OpenTag(name, type, out.length, url))
            }
            index = gt + 1
        }
        if (stack.isNotEmpty()) throw BotApiException(400, "unclosed HTML tag")
        if (entities.size > 100) throw BotApiException(400, "too many entities")
        return Parsed(out.toString(), entities)
    }

    private fun parseMarkdown(raw: String): Parsed {
        val out = StringBuilder()
        val entities = ArrayList<BotEntity>()
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '\\' && index + 1 < raw.length) {
                out.append(raw[index + 1])
                index += 2
                continue
            }
            if (raw.startsWith("```", index)) {
                val end = raw.indexOf("```", index + 3)
                if (end < 0) throw BotApiException(400, "unclosed pre block")
                val start = out.length
                out.append(raw.substring(index + 3, end))
                entities.add(BotEntity("pre", start, out.length - start))
                index = end + 3
                continue
            }
            if (ch == '`') {
                val end = raw.indexOf('`', index + 1)
                if (end < 0) {
                    out.append(ch)
                    index++
                    continue
                }
                val start = out.length
                out.append(raw.substring(index + 1, end))
                entities.add(BotEntity("code", start, out.length - start))
                index = end + 1
                continue
            }
            if (ch == '[') {
                val close = raw.indexOf(']', index + 1)
                val openParen = if (close > 0) raw.indexOf('(', close + 1) else -1
                val closeParen = if (openParen == close + 1) raw.indexOf(')', openParen + 1) else -1
                if (close > 0 && openParen == close + 1 && closeParen > 0) {
                    val label = raw.substring(index + 1, close)
                    val url = raw.substring(openParen + 1, closeParen)
                    validateUrl(url)
                    val start = out.length
                    out.append(label)
                    entities.add(BotEntity("text_link", start, out.length - start, url))
                    index = closeParen + 1
                    continue
                }
            }
            if (ch == '_' && raw.startsWith("__", index)) {
                val end = raw.indexOf("__", index + 2)
                if (end > index + 2) {
                    val start = out.length
                    out.append(raw.substring(index + 2, end))
                    entities.add(BotEntity("underline", start, out.length - start))
                    index = end + 2
                    continue
                }
            }
            if (ch == '*' || ch == '_' || ch == '~') {
                val marker = ch.toString()
                val end = raw.indexOf(marker, index + 1)
                if (end > index + 1) {
                    val type = when (ch) {
                        '*' -> "bold"
                        '_' -> "italic"
                        else -> "strikethrough"
                    }
                    val start = out.length
                    out.append(raw.substring(index + 1, end))
                    entities.add(BotEntity(type, start, out.length - start))
                    index = end + 1
                    continue
                }
            }
            out.append(ch)
            index++
        }
        if (entities.size > 100) throw BotApiException(400, "too many entities")
        return Parsed(out.toString(), entities)
    }

    private fun decode(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun validateUrl(value: String) {
        if (value.length > 2048) throw BotApiException(400, "url is too long")
        val lower = value.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw BotApiException(400, "only http(s) links are allowed")
        }
        if (value.contains(' ') || value.contains('\n')) throw BotApiException(400, "invalid url")
    }
}
