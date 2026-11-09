package com.agent.mobile.phone

/**
 * Turns the accessibility tree into a compact, indented element list for the model:
 *
 * ```
 * Screen: WhatsApp (com.whatsapp) · 1080x2400 · 14 elements
 * [e1] Text "Chats"
 * [e2] List (scroll)
 *   [e3] Item (click) @540,410
 *     [e4] Text "Anna"
 * [e5] PasswordField "Password" [content hidden] (edit)
 * ```
 *
 * Only visible nodes on screen are listed. Layout containers without text or actions are left
 * out (their children move up). Password fields never show their content. The text is capped by
 * [maxChars] and [maxElements] to keep the token budget small; ids are assigned to every listed
 * node, so elements cut from the text can still be found by [ElementMatcher].
 */
object ScreenTreeSerializer {
    const val DEFAULT_MAX_CHARS = 6_000
    const val DEFAULT_MAX_ELEMENTS = 150
    /** Hard cap on how many elements a snapshot keeps for matching. */
    const val MAX_TRACKED_ELEMENTS = 800
    private const val MAX_LABEL_CHARS = 80
    private const val MAX_INDENT = 6

    fun serialize(
        root: UiNode?,
        packageName: String,
        appLabel: String = packageName,
        screen: Bounds? = null,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxElements: Int = DEFAULT_MAX_ELEMENTS,
        filter: String? = null,
        partial: Boolean = false
    ): ScreenSnapshot {
        val elements = mutableListOf<ScreenElement>()
        if (root != null) collect(root, screen, null, 0, elements)

        val query = filter?.trim()?.takeIf { it.isNotEmpty() }
        val shown = if (query == null) elements else elements.filter { matchesFilter(it, query) }
        val header = buildString {
            append("Screen: ").append(appLabel)
            if (appLabel != packageName) append(" (").append(packageName).append(')')
            if (screen != null && !screen.isEmpty()) append(" · ").append(screen.width).append('x').append(screen.height)
            append(" · ").append(elements.size).append(" elements")
            if (query != null) append(" · filter \"").append(query).append("\": ").append(shown.size).append(" match")
            if (partial) append(" · partial (the app answered slowly; read again for the rest)")
        }

        val out = StringBuilder(header)
        var listed = 0
        var truncated = false
        for (el in shown) {
            val line = formatLine(el, indented = query == null)
            if (listed >= maxElements || out.length + line.length + 1 > maxChars) {
                truncated = true
                break
            }
            out.append('\n').append(line)
            listed++
        }
        if (shown.isEmpty()) {
            out.append('\n').append(if (query != null) "(no element matches the filter)" else "(no readable elements; try screen_capture or wait and read again)")
        }
        if (truncated) {
            val rest = shown.size - listed
            out.append("\n… ").append(rest).append(" more element").append(if (rest == 1) "" else "s")
                .append(" not shown. Scroll, or call screen_read with a filter.")
        }
        return ScreenSnapshot(
            packageName = packageName,
            appLabel = appLabel,
            elements = elements,
            text = out.toString(),
            truncated = truncated,
            screenWidth = screen?.width ?: 0,
            screenHeight = screen?.height ?: 0,
            partial = partial
        )
    }

    /** True when the node carries something the model can read or act on. */
    internal fun isInformative(node: UiNode): Boolean =
        node.password || node.clickable || node.longClickable || node.editable || node.scrollable || node.checkable ||
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
            (node.editable && !node.hint.isNullOrBlank())

    private fun collect(
        node: UiNode,
        screen: Bounds?,
        parentId: String?,
        depth: Int,
        out: MutableList<ScreenElement>
    ) {
        if (out.size >= MAX_TRACKED_ELEMENTS) return
        if (!node.visible || node.bounds.isEmpty()) return
        if (screen != null && !screen.isEmpty() && !node.bounds.intersects(screen)) return

        var childParent = parentId
        var childDepth = depth
        if (isInformative(node)) {
            val id = "e${out.size + 1}"
            // Never keep a password's text, not even in memory for matching.
            val safe = if (node.password) node.copy(text = null, contentDescription = null, children = emptyList())
            else node.copy(children = emptyList())
            out += ScreenElement(id, safe, parentId, depth)
            childParent = id
            childDepth = depth + 1
        }
        if (node.password) return
        node.children.forEach { collect(it, screen, childParent, childDepth, out) }
    }

    private fun matchesFilter(el: ScreenElement, query: String): Boolean {
        val n = el.node
        return listOfNotNull(if (n.password) null else n.text, if (n.password) null else n.contentDescription, n.hint, n.viewId)
            .any { it.contains(query, ignoreCase = true) }
    }

    internal fun clean(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_LABEL_CHARS) oneLine else oneLine.take(MAX_LABEL_CHARS - 1) + "…"
    }

    private fun quote(text: String): String = "\"" + text.replace("\"", "'") + "\""

    internal fun formatLine(el: ScreenElement, indented: Boolean = true): String {
        val n = el.node
        val sb = StringBuilder()
        if (indented) repeat(minOf(el.depth, MAX_INDENT)) { sb.append("  ") }
        sb.append('[').append(el.id).append("] ").append(n.role)
        if (n.password) {
            clean(n.hint)?.let { sb.append(' ').append(quote(it)) }
            sb.append(" [content hidden]")
        } else {
            val text = clean(n.text)
            val desc = clean(n.contentDescription)
            if (text != null) sb.append(' ').append(quote(text))
            if (desc != null && desc != text) {
                if (text == null) sb.append(' ').append(quote(desc)) else sb.append(" desc=").append(quote(desc))
            }
            if (text == null && n.editable) {
                clean(n.hint)?.let { sb.append(" hint=").append(quote(it)) }
                if (n.showingHint) sb.append(" empty")
            }
        }
        val flags = buildList {
            if (n.clickable) add("click")
            if (n.longClickable) add("long")
            if (n.editable) add("edit")
            if (n.scrollable) add("scroll")
            if (n.checkable) add(if (n.checked) "on" else "off")
            if (n.focused) add("focused")
            if (n.selected) add("selected")
            if (!n.enabled) add("disabled")
        }
        if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(')')
        if (n.clickable || n.editable || n.longClickable) {
            sb.append(" @").append(n.bounds.centerX).append(',').append(n.bounds.centerY)
        }
        return sb.toString()
    }
}
