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
 */
class MemoryStore(
    private val file: File,
    private val maxChars: Int = DEFAULT_MAX_CHARS
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 6_000
        private const val MAX_ENTRY_CHARS = 500
        private val ENTRY_PREFIX = Regex("""^-\s*(\[\d{4}-\d{2}-\d{2}]\s*)?""")
        /** Entries learned from failed runs; they rotate, facts about the user stay. */
        const val LESSON_PREFIX = "Lesson:"
        const val MAX_LESSONS = 15
    }

    private fun isLesson(entry: String) = stripPrefix(entry).startsWith(LESSON_PREFIX, ignoreCase = true)

    /** Stores one lesson from a failed run (problem, cause, what worked). */
    fun rememberLesson(lesson: String, now: Date = Date()): String =
        remember("$LESSON_PREFIX ${lesson.trim().removePrefix(LESSON_PREFIX).trim()}", now)

    private val lock = Any()

    fun entries(): List<String> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.startsWith("-") }
    }

    /** The memory as it is shown to the model (empty string when there is none). */
    fun asPromptText(): String = entries().joinToString("\n")

    /**
     * Adds a fact. Returns a short status message for the model.
     * Exact duplicates (ignoring date and case) are not stored twice.
     */
    fun remember(note: String, now: Date = Date()): String = synchronized(lock) {
        val clean = note.replace(Regex("\\s+"), " ").trim().take(MAX_ENTRY_CHARS)
        if (clean.isEmpty()) return "Nothing to remember: the note was empty."
        val current = entries().toMutableList()
        if (current.any { stripPrefix(it).equals(clean, ignoreCase = true) }) {
            return "Already in memory."
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        current += "- [$date] $clean"
        var dropped = 0
        // Over the limit: old lessons go first, then the oldest facts. Too many lessons: the oldest goes.
        while (current.count { isLesson(it) } > MAX_LESSONS) {
            current.removeAt(current.indexOfFirst { isLesson(it) })
            dropped++
        }
        while (current.size > 1 && current.joinToString("\n").length > maxChars) {
            val oldestLesson = current.dropLast(1).indexOfFirst { isLesson(it) }
            current.removeAt(if (oldestLesson >= 0) oldestLesson else 0)
            dropped++
        }
        write(current)
        if (dropped > 0) "Saved. $dropped oldest entr${if (dropped == 1) "y was" else "ies were"} removed to stay within the memory limit."
        else "Saved to long-term memory."
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
