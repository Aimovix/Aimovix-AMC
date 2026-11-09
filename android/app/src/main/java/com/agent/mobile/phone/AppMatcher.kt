package com.agent.mobile.phone

/** An app that can be opened from the launcher. */
data class AppEntry(val label: String, val packageName: String)

/** Finds the app the user or model means by name ("whatsapp", "Google Maps", "com.spotify.music"). */
object AppMatcher {

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * Best match for [query]: exact package, exact name, name without spaces/punctuation, name
     * starting with the query, then name containing it. Shorter names win ties ("Maps" over
     * "Maps Go"). Returns null when nothing matches.
     */
    fun bestMatch(apps: List<AppEntry>, query: String): AppEntry? {
        val q = query.trim()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it }
        val nq = norm(q)
        if (nq.isEmpty()) return null
        fun rank(app: AppEntry): Int {
            val label = app.label.trim()
            val nl = norm(label)
            return when {
                label.equals(q, ignoreCase = true) -> 6
                nl == nq -> 5
                nl.startsWith(nq) -> 4
                label.split(Regex("\\s+")).any { norm(it) == nq } -> 3
                nl.contains(nq) -> 2
                nq.length >= 4 && norm(app.packageName.substringAfterLast('.')) == nq -> 1
                else -> 0
            }
        }
        return apps.map { it to rank(it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
            .firstOrNull()?.first
    }

    /** Sorted, de-duplicated list as text for the model; blocked apps are marked. */
    fun format(apps: List<AppEntry>, filter: String?, blocklist: PackageBlocklist, max: Int = 300): String {
        val f = filter?.trim().orEmpty()
        val shown = apps.distinctBy { it.packageName }
            .filter { f.isEmpty() || it.label.contains(f, ignoreCase = true) || it.packageName.contains(f, ignoreCase = true) }
            .sortedBy { it.label.lowercase() }
        if (shown.isEmpty()) return if (f.isEmpty()) "No launchable apps found." else "No installed app matches \"$f\"."
        return buildString {
            append(shown.size).append(" app").append(if (shown.size == 1) "" else "s")
            if (f.isNotEmpty()) append(" matching \"").append(f).append('"')
            append(":\n")
            shown.take(max).forEach { app ->
                append("- ").append(app.label).append(" (").append(app.packageName).append(')')
                if (blocklist.isBlocked(app.packageName, app.label)) append(" [blocked for AMC]")
                append('\n')
            }
            if (shown.size > max) append("… ").append(shown.size - max).append(" more; use a filter.\n")
        }.trimEnd()
    }
}
