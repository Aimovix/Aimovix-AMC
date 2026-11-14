package com.agent.mobile.agent

/**
 * Cuts files and web pages into windows that fit a tool result, with a header that says where
 * the window is and how to read the next one. Without it, long texts reached the model only as
 * head + tail and the middle could never be read.
 */
object TextWindow {

    /** Characters per window; stays below the tool-output compaction limit of the engine. */
    const val WINDOW_CHARS = 5_000

    /**
     * Lines [offset] (1-based) to [offset] + [limit] - 1 of [text]. Without a limit, as many
     * lines as fit into [maxChars]; a single over-long line is cut.
     */
    fun lines(text: String, offset: Int?, limit: Int?, maxChars: Int = WINDOW_CHARS): String {
        if (text.isEmpty()) return "(empty file)"
        val all = text.split('\n').let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val total = all.size
        val start = (offset ?: 1).coerceAtLeast(1)
        if (start > total) return "[The file has only $total lines; offset $start is past the end.]"
        val wanted = limit?.coerceAtLeast(1)
        val out = StringBuilder()
        var end = start - 1
        var cut = false
        while (end < total) {
            if (wanted != null && end - start + 1 >= wanted) break
            val line = all[end]
            val needed = line.length + 1
            if (out.length + needed > maxChars) {
                if (out.isEmpty()) {
                    out.append(line.take(maxChars)).append('\n')
                    end++
                    cut = true
                }
                break
            }
            out.append(line).append('\n')
            end++
        }
        val whole = start == 1 && end == total && !cut
        if (whole) return out.toString().trimEnd('\n')
        val header = buildString {
            append("[Lines ").append(start).append('-').append(end).append(" of ").append(total)
            append(" (").append(text.length).append(" characters in total)")
            if (cut) append("; line ").append(end).append(" was cut at ").append(maxChars).append(" characters")
            if (end < total) append(". Next: read_file with offset=").append(end + 1)
            append(".]\n")
        }
        return header + out.toString().trimEnd('\n')
    }

    /** Characters [offset] until [offset] + [maxChars] of a fetched page. */
    fun chars(text: String, offset: Int?, maxChars: Int = WINDOW_CHARS): String {
        val start = (offset ?: 0).coerceAtLeast(0)
        if (start == 0 && text.length <= maxChars) return text
        if (start >= text.length) return "[The page has only ${text.length} characters; offset $start is past the end.]"
        val end = minOf(text.length, start + maxChars)
        val header = buildString {
            append("[Characters ").append(start).append('-').append(end).append(" of ").append(text.length)
            if (end < text.length) append(". Next: web_fetch with the same url and offset=").append(end)
            append(".]\n")
        }
        return header + text.substring(start, end)
    }

    /** Result of [replace]: the new text, or why nothing was replaced. */
    data class Edit(val text: String?, val count: Int, val error: String?)

    /**
     * Replaces [old] with [new] in [text]. Unless [all] is set, [old] must occur exactly once, so
     * an edit never lands in the wrong place.
     */
    fun replace(text: String, old: String, new: String, all: Boolean): Edit {
        if (old.isEmpty()) return Edit(null, 0, "old_text must not be empty.")
        val count = Regex.escape(old).toRegex().findAll(text).count()
        return when {
            count == 0 -> Edit(
                null, 0,
                "old_text was not found in the file. Read the file again (read_file) and copy the exact text, " +
                    "including spaces, indentation and line breaks."
            )
            count > 1 && !all -> Edit(
                null, count,
                "old_text occurs $count times. Include more surrounding lines so it is unique, or set replace_all=true."
            )
            else -> Edit(if (all) text.replace(old, new) else text.replaceFirst(old, new), count, null)
        }
    }
}
