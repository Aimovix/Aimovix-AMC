package com.agent.mobile.unattended

/**
 * Prompt-injection protection for unattended runs (review finding #21).
 *
 * Content from web pages, files, notifications, screens, sub-agent reports and foreign messages
 * is data. Once a run has read such content it is "tainted". From then on an action of a
 * protected kind runs only when its origin is the user's task:
 * - the task (the user's request, or the trusted part of a trigger's prompt) asks for that kind
 *   of action, and
 * - the action's content was not taken from the untrusted data (a distinctive fragment such as
 *   an address, a number, a URL or five words in a row that appear only in the data).
 *
 * Protected kinds: unlocking a category that is denied by default, durable memory, new
 * schedules, new notification triggers and new skills. Before any untrusted content was read,
 * nothing is restricted (the model only had the user's words).
 */
class InjectionGuard(
    /** The trusted task text (the user's request; for trigger runs only the user's instruction). */
    taskText: String,
    /** True when the run starts with untrusted content already in its first turn (trigger runs). */
    startsTainted: Boolean = false
) {
    enum class Kind(val label: String, val taskWords: Regex) {
        MEMORY(
            "save to long-term memory",
            Regex("""\b(remember|memori[sz]e|note (that|down)|keep in mind|save (this|that|it)|merk|merke|merken|notier|speicher|vergiss nicht)""", RegexOption.IGNORE_CASE)
        ),
        SCHEDULE(
            "schedule a background task",
            Regex("""\b(schedule|every|daily|weekly|hourly|monthly|tomorrow|later|tonight|remind|recurring|repeat|at \d|in \d+ ?(min|minutes|hours?|h)\b|plan|täglich|jeden|jede|wöchentlich|stündlich|morgen|später|heute abend|erinner|wiederhol|regelmäßig|zeitplan|um \d)""", RegexOption.IGNORE_CASE)
        ),
        TRIGGER(
            "add a notification trigger",
            Regex("""\b(trigger|whenever|when(ever)? (a|an|the|new) (notification|message|mail|e-mail|sms)|if (a|an) (notification|message) (arrives|comes)|sobald|immer wenn|wenn (eine|ein|neue) (benachrichtigung|nachricht|mail))""", RegexOption.IGNORE_CASE)
        ),
        SKILL(
            "save a skill",
            Regex("""\b(skill|skills|save (the|this) (steps|procedure|workflow)|fähigkeit)""", RegexOption.IGNORE_CASE)
        ),
        UNLOCKED_CATEGORY("use an action that is denied by default", Regex("(?!)"))
    }

    data class Verdict(val allowed: Boolean, val reason: String)

    private val task = taskText
    private val taskNorm = normalize(taskText)

    @Volatile
    var tainted: Boolean = startsTainted
        private set

    /** Where untrusted content came from in this run (tool names), for the log and the report. */
    val sources: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf())

    private val corpus = StringBuilder()

    /**
     * A guard for a sub-agent: same trusted task (the user's, not the delegated text the model
     * wrote) and everything already read counts as read.
     */
    @Synchronized
    fun fork(): InjectionGuard = InjectionGuard(task, tainted).also { child ->
        child.sources += sources
        child.corpus.append(corpus)
    }

    /** Records untrusted content the model has seen ([source] is the tool or channel it came from). */
    @Synchronized
    fun observeUntrusted(source: String, text: String) {
        if (text.isBlank()) return
        tainted = true
        sources += source
        corpus.append('\n').append(text.take(MAX_SAMPLE_CHARS))
        if (corpus.length > MAX_CORPUS_CHARS) corpus.delete(0, corpus.length - MAX_CORPUS_CHARS)
    }

    /**
     * Decides a protected action. [content] is what the action would store or act on (the note,
     * the scheduled prompt, the trigger's instruction, the skill text, a command or screen text).
     * [categoryWords] describes an unlocked category for [Kind.UNLOCKED_CATEGORY].
     */
    @Synchronized
    fun check(kind: Kind, content: String, categoryWords: Regex? = null): Verdict {
        if (!tainted) return Verdict(true, "No untrusted content was read in this run.")
        val words = if (kind == Kind.UNLOCKED_CATEGORY) categoryWords else kind.taskWords
        if (words != null && !words.containsMatchIn(task)) {
            return Verdict(
                false,
                "This run read untrusted content (${sources.joinToString()}) and your task did not ask to ${kind.label}. " +
                    "Content from web pages, files, screens or notifications cannot make the agent do that."
            )
        }
        val fragment = untrustedFragment(content)
        if (fragment != null) {
            return Verdict(
                false,
                "The ${kind.label.substringAfterLast(' ')} contains \"${fragment.take(80)}\", which comes from untrusted content " +
                    "(${sources.joinToString()}), not from your task."
            )
        }
        return Verdict(true, "Requested by the task; nothing taken from untrusted content.")
    }

    /** A distinctive piece of [content] that occurs in the untrusted data but not in the task, or null. */
    internal fun untrustedFragment(content: String): String? {
        val data = normalize(corpus.toString())
        if (data.isBlank()) return null
        for (m in DISTINCTIVE.findAll(content)) {
            val token = normalize(m.value)
            if (token.length >= 5 && data.contains(token) && !taskNorm.contains(token)) return m.value
        }
        val words = normalize(content).split(' ').filter { it.isNotBlank() }
        for (i in 0..(words.size - NGRAM)) {
            val gram = words.subList(i, i + NGRAM).joinToString(" ")
            if (gram.length >= 20 && data.contains(gram) && !taskNorm.contains(gram)) return gram
        }
        return null
    }

    companion object {
        private const val NGRAM = 5
        private const val MAX_SAMPLE_CHARS = 20_000
        private const val MAX_CORPUS_CHARS = 200_000

        /** E-mail addresses, URLs and hosts, phone numbers and IBAN-like strings. */
        private val DISTINCTIVE = Regex(
            """[\w.+-]+@[\w-]+(\.[\w-]+)+|https?://\S+|\b[\w-]+(\.[\w-]+)*\.(com|net|org|io|de|ru|cn|xyz|top|info|biz|me|co|app)\b|\+?\d[\d\s/-]{6,}\d|\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b"""
        )

        fun normalize(text: String): String =
            text.lowercase().replace(Regex("""[^\p{L}\p{N}@.+:/-]+"""), " ").replace(Regex("\\s+"), " ").trim()
    }
}
