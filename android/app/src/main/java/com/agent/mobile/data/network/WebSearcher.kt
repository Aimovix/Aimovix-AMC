package com.agent.mobile.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Keyless web search for the agent's `web_search` tool. Queries the HTML version of
 * DuckDuckGo (no API key, no account) and returns title, URL and snippet per result.
 * The agent then reads promising results with `web_fetch`.
 */
class WebSearcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val endpoint: String = "https://html.duckduckgo.com/html/"
) {
    data class Hit(val title: String, val url: String, val snippet: String)

    data class Result(val ok: Boolean, val hits: List<Hit>, val error: String = "")

    suspend fun search(query: String, maxResults: Int = 8): Result = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Result(false, emptyList(), "The search query is empty.")
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AMC-Agent/1.4")
            .header("Accept", "text/html")
            .post(FormBody.Builder().add("q", q.take(400)).add("kl", "wt-wt").build())
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result(false, emptyList(), "Search failed with HTTP ${response.code}.")
                }
                val hits = parseDuckDuckGoHtml(html).take(maxResults.coerceIn(1, 15))
                when {
                    hits.isNotEmpty() -> Result(true, hits)
                    looksBlocked(html) -> Result(
                        false, emptyList(),
                        "The search engine refused the automated request (captcha). Try again later or use web_fetch " +
                            "on a site's own search page, e.g. https://en.wikipedia.org/w/index.php?search=<terms>."
                    )
                    else -> Result(true, emptyList())
                }
            }
        } catch (e: java.net.UnknownHostException) {
            Result(false, emptyList(), "No internet connection (cannot resolve the search host).")
        } catch (e: java.io.IOException) {
            Result(false, emptyList(), "Network error during search: ${e.message}")
        }
    }

    companion object {
        private val RESULT_BLOCK = Regex(
            """<div[^>]+class="[^"]*\bresult\b[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        private val TITLE_LINK = Regex(
            """<a[^>]+class="[^"]*\bresult__a\b[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TITLE_LINK_HREF_FIRST = Regex(
            """<a[^>]+href="([^"]+)"[^>]*class="[^"]*\bresult__a\b[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val SNIPPET = Regex(
            """<(?:a|div|td)[^>]+class="[^"]*\bresult__snippet\b[^"]*"[^>]*>(.*?)</(?:a|div|td)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TAGS = Regex("<[^>]+>")
        private val SPACES = Regex("\\s+")

        /** Extracts organic results (ads are skipped) from DuckDuckGo's HTML page. */
        fun parseDuckDuckGoHtml(html: String): List<Hit> {
            val starts = RESULT_BLOCK.findAll(html).map { it.range.first }.toList()
            if (starts.isEmpty()) return emptyList()
            val hits = mutableListOf<Hit>()
            val seen = mutableSetOf<String>()
            for ((i, start) in starts.withIndex()) {
                val end = if (i + 1 < starts.size) starts[i + 1] else html.length
                val block = html.substring(start, end)
                val opening = block.substringBefore('>')
                if (opening.contains("result--ad", ignoreCase = true)) continue
                val link = TITLE_LINK.find(block) ?: TITLE_LINK_HREF_FIRST.find(block) ?: continue
                val url = resolveUrl(decodeEntities(link.groupValues[1])) ?: continue
                if (url.contains("duckduckgo.com/y.js") || !seen.add(url)) continue
                val title = clean(link.groupValues[2])
                val snippet = SNIPPET.find(block)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
                if (title.isNotEmpty()) hits += Hit(title, url, snippet)
            }
            return hits
        }

        /** DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=<encoded target>. */
        internal fun resolveUrl(href: String): String? {
            val h = href.trim()
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(h)?.groupValues?.get(1)
            val target = if (uddg != null) {
                try { URLDecoder.decode(uddg, "UTF-8") } catch (e: Exception) { return null }
            } else if (h.startsWith("//")) "https:$h" else h
            return target.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        private fun looksBlocked(html: String): Boolean =
            html.contains("anomaly", ignoreCase = true) || html.contains("captcha", ignoreCase = true)

        private fun clean(fragment: String): String =
            SPACES.replace(decodeEntities(TAGS.replace(fragment, "")), " ").trim()

        private fun decodeEntities(s: String): String = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d{1,6});")) { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace("&amp;", "&")

        /** Compact text form of the hits for the model. */
        fun format(query: String, hits: List<Hit>): String {
            if (hits.isEmpty()) return "No results for \"$query\". Try other keywords."
            return buildString {
                append("Search results for \"").append(query).append("\":\n")
                hits.forEachIndexed { i, h ->
                    append('\n').append(i + 1).append(". ").append(h.title).append('\n')
                    append("   ").append(h.url).append('\n')
                    if (h.snippet.isNotEmpty()) append("   ").append(h.snippet.take(300)).append('\n')
                }
                append("\nUse web_fetch on the most relevant URLs to read them.")
            }
        }
    }
}
