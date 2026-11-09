package com.agent.mobile.phone

/**
 * Finds the element the model means: by id from the last screen_read ("e12"), or by visible
 * text / description. Also re-locates an element in a fresh capture after the screen changed.
 */
object ElementMatcher {

    private val ID_REGEX = Regex("""^\s*[\[#(]?\s*(e\d{1,4})\s*[\])]?\s*$""", RegexOption.IGNORE_CASE)

    /** "e12", "[e12]", "#e12" → "e12"; anything else → null. */
    fun parseId(target: String): String? = ID_REGEX.matchEntire(target)?.groupValues?.get(1)?.lowercase()

    /** Resolves an id or a text query. Returns null when nothing matches. */
    fun resolve(snapshot: ScreenSnapshot, target: String): ScreenElement? {
        val t = target.trim()
        if (t.isEmpty()) return null
        parseId(t)?.let { return snapshot.byId(it) }
        return findByText(snapshot, t)
    }

    /**
     * Best text match. Ranking: exact text, exact description, exact hint/view-id suffix,
     * text starts with, text contains, description contains. Within a rank, actionable elements
     * win, then the first in reading order. Password fields only match by hint.
     */
    fun findByText(snapshot: ScreenSnapshot, query: String): ScreenElement? {
        val q = query.trim().trim('"', '\'')
        if (q.isEmpty()) return null
        var best: ScreenElement? = null
        var bestScore = 0
        for (el in snapshot.elements) {
            val score = score(el, q)
            if (score > bestScore) {
                best = el
                bestScore = score
            }
        }
        return best
    }

    private fun score(el: ScreenElement, q: String): Int {
        val n = el.node
        val text = if (n.password) null else n.text?.trim()
        val desc = if (n.password) null else n.contentDescription?.trim()
        val hint = n.hint?.trim()
        val idSuffix = n.viewId?.substringAfterLast('/')
        val base = when {
            text != null && text.equals(q, ignoreCase = true) -> 100
            desc != null && desc.equals(q, ignoreCase = true) -> 95
            hint != null && hint.equals(q, ignoreCase = true) -> 90
            idSuffix != null && idSuffix.equals(q, ignoreCase = true) -> 85
            text != null && text.startsWith(q, ignoreCase = true) -> 70
            text != null && text.contains(q, ignoreCase = true) -> 60
            desc != null && desc.contains(q, ignoreCase = true) -> 55
            hint != null && hint.contains(q, ignoreCase = true) -> 50
            else -> 0
        }
        if (base == 0) return 0
        val actionable = n.clickable || n.editable || n.longClickable || n.checkable
        return base * 10 + (if (actionable) 5 else 0) + (if (n.enabled) 1 else 0)
    }

    /** Smallest element that contains the point (what a tap there would hit). */
    fun elementAt(snapshot: ScreenSnapshot, x: Int, y: Int): ScreenElement? =
        snapshot.elements.filter { it.node.bounds.contains(x, y) }.minByOrNull { it.node.bounds.area() }

    /** The element itself if it is clickable, otherwise its nearest clickable ancestor. */
    fun clickTarget(snapshot: ScreenSnapshot, element: ScreenElement, longPress: Boolean = false): ScreenElement {
        var current: ScreenElement? = element
        while (current != null) {
            val n = current.node
            if (if (longPress) n.longClickable else n.clickable) return current
            current = current.parentId?.let { snapshot.byId(it) }
        }
        return element
    }

    /**
     * The label a tap on [element] stands for: its own label or, for an unlabeled clickable
     * container (a list row), the first labeled descendant. Used for risk checks.
     */
    fun effectiveLabel(snapshot: ScreenSnapshot, element: ScreenElement): String {
        val own = element.label
        if (own.isNotBlank()) return own
        val viewId = element.node.viewId?.substringAfterLast('/').orEmpty()
        val child = snapshot.elements.firstOrNull { isDescendant(snapshot, it, element) && it.label.isNotBlank() }
        return child?.label ?: viewId
    }

    private fun isDescendant(snapshot: ScreenSnapshot, candidate: ScreenElement, ancestor: ScreenElement): Boolean {
        var p = candidate.parentId
        var guard = 0
        while (p != null && guard++ < 64) {
            if (p == ancestor.id) return true
            p = snapshot.byId(p)?.parentId
        }
        return false
    }

    /**
     * Finds [old] (from an earlier snapshot) in [fresh]. Same view id and text first, then same
     * class and text, then same class and nearly the same bounds.
     */
    fun relocate(old: ScreenElement, fresh: ScreenSnapshot): ScreenElement? {
        val o = old.node
        val candidates = fresh.elements
        fun sameText(n: UiNode) = !o.password && (n.text ?: "") == (o.text ?: "") && (n.contentDescription ?: "") == (o.contentDescription ?: "")
        if (!o.viewId.isNullOrBlank()) {
            candidates.filter { it.node.viewId == o.viewId && (o.password || sameText(it.node)) }
                .minByOrNull { distance(it.node.bounds, o.bounds) }?.let { return it }
        }
        val hasText = !o.password && (!o.text.isNullOrBlank() || !o.contentDescription.isNullOrBlank())
        if (hasText) {
            candidates.filter { it.node.className == o.className && sameText(it.node) }
                .minByOrNull { distance(it.node.bounds, o.bounds) }?.let { return it }
        }
        return candidates.filter { it.node.className == o.className && distance(it.node.bounds, o.bounds) <= 24 }
            .minByOrNull { distance(it.node.bounds, o.bounds) }
    }

    private fun distance(a: Bounds, b: Bounds): Int =
        maxOf(kotlin.math.abs(a.left - b.left), kotlin.math.abs(a.top - b.top), kotlin.math.abs(a.right - b.right), kotlin.math.abs(a.bottom - b.bottom))
}
