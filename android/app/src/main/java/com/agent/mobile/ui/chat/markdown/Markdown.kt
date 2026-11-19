package com.agent.mobile.ui.chat.markdown

/**
 * A small Markdown reader for chat replies (Teil E, E1). It knows the parts models actually use:
 * headings, paragraphs, bullet and numbered lists, quotes, rules and fenced code blocks, plus
 * inline bold, italic, code and links. Anything else stays plain text, so nothing is lost. An
 * unclosed code fence (while a reply is still streaming) is a code block up to the end.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<String>, val start: Int = 1) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    object Rule : MdBlock()
}

/** One inline span of a line: plain, bold, italic, code or a link. */
sealed class MdSpan {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MdSpan()
    data class Code(val text: String) : MdSpan()
    data class Link(val label: String, val url: String) : MdSpan()
}

object Markdown {

    private val FENCE = Regex("""^\s*(```|~~~)\s*([\w+#.-]*)\s*$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*(\d{1,9})[.)]\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                out += MdBlock.Paragraph(paragraph.joinToString("\n").trim())
                paragraph.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            when {
                fence != null -> {
                    flush()
                    val marker = fence.groupValues[1]
                    val language = fence.groupValues[2]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != marker) {
                        code += lines[i]
                        i++
                    }
                    out += MdBlock.Code(language, code.joinToString("\n").trimEnd('\n'))
                }
                line.isBlank() -> flush()
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                RULE.matches(line) -> {
                    flush()
                    out += MdBlock.Rule
                }
                BULLET.matches(line) || ORDERED.matches(line) -> {
                    flush()
                    val ordered = !BULLET.matches(line)
                    val start = if (ordered) ORDERED.find(line)!!.groupValues[1].toIntOrNull() ?: 1 else 1
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val m = if (ordered) ORDERED.find(l) else BULLET.find(l)
                        when {
                            m != null -> items += m.groupValues[if (ordered) 2 else 1]
                            // An indented line continues the item before it.
                            l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty() && !BULLET.matches(l) && !ORDERED.matches(l) ->
                                items[items.lastIndex] = items.last() + " " + l.trim()
                            else -> break
                        }
                        i++
                    }
                    out += MdBlock.ListBlock(ordered, items, start)
                    continue
                }
                QUOTE.matches(line) -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && QUOTE.matches(lines[i])) {
                        quoted += QUOTE.find(lines[i])!!.groupValues[1]
                        i++
                    }
                    out += MdBlock.Quote(quoted.joinToString("\n").trim())
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return out
    }

    private val INLINE = Regex(
        """`([^`\n]+)`""" +                                   // 1 code
            """|\[([^\]\n]+)]\((https?://[^)\s]+)\)""" +      // 2,3 link
            """|\*\*(?!\s)([^*\n]+?)(?<!\s)\*\*|__(?!\s)([^_\n]+?)(?<!\s)__""" + // 4,5 bold
            """|(?<![\w*])\*(?![\s*])([^*\n]+?)(?<![\s*])\*(?![\w*])""" +     // 6 italic
            """|(?<![\w_])_(?![\s_])([^_\n]+?)(?<![\s_])_(?![\w_])""" +          // 7 italic
            """|(https?://[^\s<>()\[\]]+[^\s<>()\[\].,;:!?'"])"""  // 8 bare link
    )

    /** Splits one block's text into inline spans. */
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out += MdSpan.Text(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> MdSpan.Code(g[1])
                g[2].isNotEmpty() -> MdSpan.Link(g[2], g[3])
                g[4].isNotEmpty() -> MdSpan.Text(g[4], bold = true)
                g[5].isNotEmpty() -> MdSpan.Text(g[5], bold = true)
                g[6].isNotEmpty() -> MdSpan.Text(g[6], italic = true)
                g[7].isNotEmpty() -> MdSpan.Text(g[7], italic = true)
                else -> MdSpan.Link(g[8], g[8])
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdSpan.Text(text.substring(last))
        return out
    }

    /** Plain text of a reply without Markdown marks (for reading aloud and copying as text). */
    fun plain(text: String): String = parse(text).joinToString("\n\n") { block ->
        when (block) {
            is MdBlock.Heading -> plainInline(block.text)
            is MdBlock.Paragraph -> plainInline(block.text)
            is MdBlock.ListBlock -> block.items.mapIndexed { i, item ->
                (if (block.ordered) "${block.start + i}. " else "• ") + plainInline(item)
            }.joinToString("\n")
            is MdBlock.Quote -> plainInline(block.text)
            is MdBlock.Code -> block.code
            MdBlock.Rule -> ""
        }
    }.trim()

    private fun plainInline(text: String) = inline(text).joinToString("") {
        when (it) {
            is MdSpan.Text -> it.text
            is MdSpan.Code -> it.text
            is MdSpan.Link -> it.label
        }
    }
}
