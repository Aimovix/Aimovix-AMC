package com.agent.mobile.phone

/**
 * The change between two readings of the same screen, so the model gets a few lines instead of
 * the whole element list again. Elements are matched by what they are (role, view id, label;
 * the hint for input fields), not by their position in the list, so an inserted row does not
 * make everything below look changed. Ids in the output are those of the new reading.
 *
 * ```
 * Screen: Settings (com.android.settings) · Δ since your last reading: +1 −1 ~1, 23 unchanged
 * + [e7] Switch "Dark theme" (click, on)
 * ~ [e3] Field "Hello" (edit, focused)
 * − Text "Loading…"
 * ```
 */
object ScreenDiff {
    /** Above this share of changed elements the full reading is clearer. */
    const val MAX_CHANGED_RATIO = 0.5

    /** The delta text, or null when a full reading should be sent (other app, mostly changed, partial). */
    fun delta(old: ScreenSnapshot?, new: ScreenSnapshot): String? {
        if (old == null || old.packageName != new.packageName || new.partial || old.partial) return null
        val before = keyed(old)
        val after = keyed(new)
        val added = after.keys - before.keys
        val removed = before.keys - after.keys
        val changed = after.keys.intersect(before.keys).filter { line(after.getValue(it)) != line(before.getValue(it)) }
        val total = maxOf(before.size, after.size, 1)
        if ((added.size + removed.size + changed.size).toDouble() / total > MAX_CHANGED_RATIO) return null
        val unchanged = after.size - added.size - changed.size
        val out = StringBuilder()
        out.append("Screen: ").append(new.appLabel)
        if (new.appLabel != new.packageName) out.append(" (").append(new.packageName).append(')')
        out.append(" · Δ since your last reading: +").append(added.size).append(" −").append(removed.size)
            .append(" ~").append(changed.size).append(", ").append(unchanged).append(" unchanged")
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            out.append("\n(no visible change)")
            return out.toString()
        }
        // New and changed elements in reading order, then what disappeared.
        val changedSet = changed.toSet()
        after.forEach { (key, el) ->
            when (key) {
                in added -> out.append("\n+ ").append(ScreenTreeSerializer.formatLine(el, indented = false))
                in changedSet -> out.append("\n~ ").append(ScreenTreeSerializer.formatLine(el, indented = false))
            }
        }
        removed.forEach { key ->
            val el = before.getValue(key)
            out.append("\n− ").append(line(el))
        }
        return out.toString()
    }

    /** Elements by key, in reading order. The key is the identity plus an ordinal among equal identities. */
    private fun keyed(snapshot: ScreenSnapshot): LinkedHashMap<String, ScreenElement> {
        val counts = HashMap<String, Int>()
        val out = LinkedHashMap<String, ScreenElement>()
        snapshot.elements.forEach { el ->
            val base = identity(el)
            val ordinal = counts.getOrDefault(base, 0)
            counts[base] = ordinal + 1
            out["$base#$ordinal"] = el
        }
        return out
    }

    private fun identity(el: ScreenElement): String {
        val n = el.node
        val label = if (n.editable || n.password) n.hint.orEmpty() else el.label
        return listOf(n.role, n.viewId.orEmpty(), ScreenTreeSerializer.clean(label).orEmpty()).joinToString("|")
    }

    /** The element without its id: what the model would see, to detect a change. */
    private fun line(el: ScreenElement): String =
        ScreenTreeSerializer.formatLine(el, indented = false).substringAfter("] ")
}
