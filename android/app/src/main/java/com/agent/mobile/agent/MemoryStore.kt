package com.agent.mobile.agent

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term agent memory, stored as a small Markdown file in app-private storage
 * (inspired by the MEMORY.md file of self-hosted agents).
 *
 * Every entry is one line: `- [yyyy-MM-dd] fact`. The whole file is injected into the
 * system prompt, so it is capped at [maxChars]; when the cap is reached the oldest
 * entries are dropped first.
 *
 * Lessons learned from failed runs (review finding #24) are entries of their own kind,
 * `- [yyyy-MM-dd] [lesson] Problem: … Cause: … Works: …`. Facts stay, lessons rotate: at the
 * cap the oldest lessons go before any fact, a lesson never pushes a fact out, at most
 * [MAX_LESSONS] are kept, and a new lesson about the same problem replaces the older one.
 */
class MemoryStore(
    private val file: File,
    private val maxChars: Int = DEFAULT_MAX_CHARS
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 6_000
        private const val MAX_ENTRY_CHARS = 500
        const val MAX_LESSONS = 12
        const val LESSON_TAG = "[lesson]"
        private val ENTRY_PREFIX = Regex("""^-\s*(\[\d{4}-\d{2}-\d{2}]\s*)?""")

        fun isLesson(entry: String): Boolean = entry.replace(ENTRY_PREFIX, "").trim().startsWith(LESSON_TAG)
    }

    enum class Kind { FACT, LESSON }

    private val lock = Any()

    fun entries(): List<String> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.startsWith("-") }
    }

    /** The memory as it is shown to the model (empty string when there is none). */
    fun asPromptText(): String = entries().joinToString("\n")

    /**
     * Adds a fact (or, with [kind] LESSON, a lesson). Returns a short status message for the model.
     * Exact duplicates (ignoring date and case) are not stored twice.
     */
    fun remember(note: String, now: Date = Date(), kind: Kind = Kind.FACT): String = synchronized(lock) {
        val clean = note.replace(Regex("\\s+"), " ").trim().take(MAX_ENTRY_CHARS)
        if (clean.isEmpty()) return "Nothing to remember: the note was empty."
        val current = entries().toMutableList()
        if (current.any { stripPrefix(it).removePrefix(LESSON_TAG).trim().equals(clean, ignoreCase = true) }) {
            return "Already in memory."
        }
        val lesson = kind == Kind.LESSON
        if (lesson) {
            // A newer lesson about the same problem replaces the older one.
            val problem = problemOf(clean)
            if (problem != null) current.removeAll { isLesson(it) && problemOf(stripPrefix(it)) == problem }
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val entry = if (lesson) "- [$date] $LESSON_TAG $clean" else "- [$date] $clean"
        current += entry
        var dropped = 0
        while (current.count { isLesson(it) } > MAX_LESSONS) {
            current.removeAt(current.indexOfFirst { isLesson(it) })
            dropped++
        }
        while (current.size > 1 && current.joinToString("\n").length > maxChars) {
            // The new entry is the last one; it is never the lesson that rotates out.
            val oldestLesson = (0 until current.lastIndex).firstOrNull { isLesson(current[it]) }
            when {
                oldestLesson != null -> current.removeAt(oldestLesson)
                // Lessons rotate, facts stay: a lesson that only fits by dropping facts is not stored.
                lesson -> {
                    current.removeAt(current.lastIndex)
                    write(current)
                    return "Not saved: the memory is full of facts."
                }
                else -> current.removeAt(0)
            }
            dropped++
        }
        write(current)
        if (dropped > 0) "Saved. $dropped oldest entr${if (dropped == 1) "y was" else "ies were"} removed to stay within the memory limit."
        else "Saved to long-term memory."
    }

    /** Stores a lesson learned from a failed run (see [Kind.LESSON]). */
    fun rememberLesson(lesson: String, now: Date = Date()): String = remember(lesson, now, Kind.LESSON)

    fun lessons(): List<String> = entries().filter { isLesson(it) }

    /** The normalized "Problem:" part of a lesson (null when it has none). */
    private fun problemOf(text: String): String? {
        val m = Regex("""Problem:\s*(.+?)(?:\s+(?:Cause|Works|Fix):|$)""", RegexOption.IGNORE_CASE).find(text.removePrefix(LESSON_TAG).trim())
        return m?.groupValues?.get(1)?.lowercase()?.replace(Regex("[^a-z0-9 ]"), "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Removes every entry that contains [text] (case-insensitive). Returns how many were removed. */
    fun forget(text: String): Int = synchronized(lock) {
        val needle = text.trim()
        if (needle.isEmpty()) return 0
        val current = entries()
        val kept = current.filterNot { it.contains(needle, ignoreCase = true) }
        val removed = current.size - kept.size
        if (removed > 0) write(kept)
        removed
    }

    /** Replaces the whole memory (used by the settings editor). Non-empty lines become entries. */
    fun replaceAll(text: String) = synchronized(lock) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("-")) it else "- $it" }
        write(lines.takeLast(200))
    }

    fun clear() = synchronized(lock) {
        if (file.exists()) file.delete()
    }

    private fun stripPrefix(entry: String): String = entry.replace(ENTRY_PREFIX, "").trim()

    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
