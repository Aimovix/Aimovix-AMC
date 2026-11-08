package com.agent.mobile.channel.telegram

/**
 * Converts the agent's Markdown replies into the small HTML subset Telegram understands and
 * splits long replies into messages below Telegram's 4096 character limit.
 */
object TelegramFormat {
    const val MAX_MESSAGE_CHARS = 3_800

    private val FENCE = Regex("```[a-zA-Z0-9_+-]*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("(?<![A-Za-z0-9])__(.+?)__(?![A-Za-z0-9])")
    private val HEADING = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
    private val LINK = Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)")
    private val BULLET = Regex("^(\\s*)[*-]\\s+", RegexOption.MULTILINE)

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Markdown (as produced by LLMs) to Telegram HTML. Unknown syntax stays as plain text. */
    fun toHtml(markdown: String): String {
        val placeholders = mutableListOf<String>()
        fun hold(html: String): String {
            placeholders += html
            return "\u0000${placeholders.size - 1}\u0000"
        }
        var s = FENCE.replace(markdown) { m -> hold("<pre>" + escape(m.groupValues[1].trimEnd()) + "</pre>") }
        s = INLINE_CODE.replace(s) { m -> hold("<code>" + escape(m.groupValues[1]) + "</code>") }
        s = LINK.replace(s) { m ->
            hold("<a href=\"" + escape(m.groupValues[2]).replace("\"", "&quot;") + "\">" + escape(m.groupValues[1]) + "</a>")
        }
        s = escape(s)
        s = HEADING.replace(s) { m -> "<b>" + m.groupValues[1].trim() + "</b>" }
        s = BOLD.replace(s) { m -> "<b>" + m.groupValues[1] + "</b>" }
        s = BOLD_UNDERSCORE.replace(s) { m -> "<b>" + m.groupValues[1] + "</b>" }
        s = BULLET.replace(s) { m -> m.groupValues[1] + "• " }
        return Regex("\u0000(\\d+)\u0000").replace(s) { m -> placeholders[m.groupValues[1].toInt()] }
    }

    /** Splits [text] into chunks of at most [max] characters, preferring paragraph and line breaks. */
    fun split(text: String, max: Int = MAX_MESSAGE_CHARS): List<String> {
        val chunks = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'), window.lastIndexOf(". "), window.lastIndexOf(' '))
                .firstOrNull { it > max / 2 } ?: max
            chunks += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) chunks += rest
        return chunks.ifEmpty { listOf("(empty reply)") }
    }
}
