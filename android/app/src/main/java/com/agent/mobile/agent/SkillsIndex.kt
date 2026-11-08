package com.agent.mobile.agent

/**
 * Builds the list of installed skills that is shown to the model at the start of a run, so it
 * knows which skills exist without listing the folder first (like OpenClaw's skill registry).
 *
 * Two layouts are supported in `~/.amc/skills/`:
 * - `<name>.md` (one Markdown file per skill), and
 * - `<name>/SKILL.md` (Agent Skills / OpenClaw format with `name:` and `description:` front matter).
 */
object SkillsIndex {
    const val MARKER = "@@AMC_SKILL@@"
    private const val MAX_SKILLS = 40

    /** Read-only shell command that prints the head of every skill file, separated by [MARKER] lines. */
    const val LIST_COMMAND =
        "for f in ~/.amc/skills/*.md ~/.amc/skills/*/SKILL.md; do [ -f \"\$f\" ] || continue; " +
            "printf '\\n$MARKER%s\\n' \"\$f\"; head -c 700 \"\$f\"; done 2>/dev/null; true"

    data class Skill(val name: String, val description: String, val path: String)

    fun parse(output: String): List<Skill> =
        output.split(MARKER).drop(1).mapNotNull { chunk ->
            val path = chunk.substringBefore('\n').trim()
            if (path.isEmpty()) return@mapNotNull null
            val body = chunk.substringAfter('\n', "")
            parseOne(path, body)
        }.distinctBy { it.path }.take(MAX_SKILLS)

    internal fun parseOne(path: String, body: String): Skill {
        val lines = body.lines()
        var name: String? = null
        var description: String? = null
        var contentStart = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                lines.subList(1, end + 1).forEach { line ->
                    val key = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim().trim('"', '\'')
                    if (key == "name" && value.isNotEmpty()) name = value
                    if (key == "description" && value.isNotEmpty()) description = value
                }
                contentStart = end + 2
            }
        }
        val content = lines.drop(contentStart).map { it.trim() }.filter { it.isNotEmpty() }
        val heading = content.firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim()
        val fallbackName = if (path.endsWith("/SKILL.md")) {
            path.removeSuffix("/SKILL.md").substringAfterLast('/')
        } else {
            path.substringAfterLast('/').removeSuffix(".md")
        }
        val firstSentence = content.firstOrNull { !it.startsWith("#") && !it.startsWith("```") }
        return Skill(
            name = (name ?: fallbackName).take(60),
            description = (description ?: firstSentence ?: heading ?: "").take(200),
            path = path
        )
    }

    /** Text for the system prompt; empty when no skills are installed. */
    fun format(skills: List<Skill>): String =
        skills.joinToString("\n") { s ->
            buildString {
                append("- ").append(s.name)
                if (s.description.isNotEmpty()) append(": ").append(s.description)
                append(" (").append(s.path).append(')')
            }
        }
}
