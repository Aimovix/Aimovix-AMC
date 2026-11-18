package com.agent.mobile.agent

/**
 * Windows of large files for read_file (#14): the model reads a file piece by piece instead of
 * seeing only its beginning and end, and every window says where the next one starts.
 */
object FileWindow {
    const val DEFAULT_LINES = 300
    const val MAX_LINES = 2_000
    /** Files up to this size come whole when no window is asked for. */
    const val SMALL_FILE_CHARS = 12_000
    /** Upper bound of one window in characters (whole lines). */
    const val MAX_WINDOW_CHARS = 14_000

    fun render(text: String, offsetArg: String?, limitArg: String?): String {
        if (text.isEmpty()) return "(empty file)"
        val offset = offsetArg?.trim()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
        val explicitLimit = limitArg?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(1, MAX_LINES)
        if (offset == 1 && explicitLimit == null && text.length <= SMALL_FILE_CHARS) return text
        val lines = text.split('\n')
        val from = (offset - 1).coerceAtMost(lines.size)
        var to = minOf(lines.size, from + (explicitLimit ?: DEFAULT_LINES))
        var chars = 0
        for (i in from until to) {
            chars += lines[i].length + 1
            if (chars > MAX_WINDOW_CHARS && i > from) {
                to = i
                break
            }
        }
        val body = lines.subList(from, to).joinToString("\n").let { if (it.length > MAX_WINDOW_CHARS) it.take(MAX_WINDOW_CHARS) + "…" else it }
        val header = buildString {
            append("[lines ").append(if (from < to) from + 1 else from).append('–').append(to)
            append(" of ").append(lines.size).append(" (").append(text.length).append(" characters)")
            append(if (to < lines.size) "; next window: offset=${to + 1}" else "; end of file").append(']')
        }
        return "$header\n$body"
    }

    /** How often [needle] occurs in [text] (non-overlapping). */
    fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            count++
            at = text.indexOf(needle, at + needle.length)
        }
        return count
    }
}
