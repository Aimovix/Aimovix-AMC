package com.agent.mobile.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Native HTTP(S) GET used by the agent's `web_fetch` tool. Works without Termux and converts
 * HTML into compact readable text so it fits the model's context.
 */
class WebFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxChars: Int = 12_000
) {
    data class Result(val ok: Boolean, val statusCode: Int, val text: String)

    /**
     * Fetches [url] and returns the window of [length] characters from [start] of its readable
     * text (#14): long pages can be read piece by piece, the header names the total and the next start.
     */
    suspend fun fetch(url: String, start: Int = 0, length: Int = maxChars): Result = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            return@withContext Result(false, 0, "Only http:// and https:// URLs are supported.")
        }
        // Android allows cleartext only to this device (network_security_config): fetch other
        // hosts over https instead of failing with an unexplained cleartext error (#37).
        val target = upgradeUrl(trimmed)
        val upgraded = target != trimmed
        val request = try {
            Request.Builder()
                .url(target)
                .header("User-Agent", "AMC-Agent/1.4 (Android)")
                .header("Accept", "text/html,application/json,text/plain;q=0.9,*/*;q=0.5")
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result(false, 0, "Invalid URL: ${e.message}")
        }
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return@use Result(response.isSuccessful, response.code, "")
                val contentType = body.contentType()?.toString().orEmpty()
                if (contentType.startsWith("image/") || contentType.startsWith("video/") ||
                    contentType.startsWith("audio/") || contentType.contains("octet-stream")
                ) {
                    return@use Result(
                        false, response.code,
                        "Binary content ($contentType) cannot be shown as text. Download it with curl -o instead."
                    )
                }
                val source = body.source()
                source.request(maxBytes)
                val bytes = source.buffer.let { buf -> buf.readByteArray(minOf(buf.size, maxBytes)) }
                val raw = String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
                val looksHtml = contentType.contains("html") || raw.trimStart().startsWith("<")
                val text = if (looksHtml) htmlToText(raw) else raw
                val from = start.coerceIn(0, text.length)
                val to = minOf(text.length, from + length.coerceIn(500, MAX_WINDOW_CHARS))
                val window = text.substring(from, to)
                val header = "HTTP ${response.code} ${contentType.substringBefore(';')}".trim() +
                    (if (upgraded) " (fetched over https: AMC does not send unencrypted requests)" else "") +
                    if (from > 0 || to < text.length) {
                        "\n[characters ${from + 1}–$to of ${text.length}" +
                            (if (to < text.length) "; next window: start=$to" else "; end of page") + "]"
                    } else ""
                Result(response.isSuccessful, response.code, "$header\n\n$window")
            }
        } catch (e: java.net.UnknownHostException) {
            Result(false, 0, "Cannot resolve host. Check the URL and the internet connection.")
        } catch (e: java.io.IOException) {
            Result(false, 0, "Network error: ${e.message}")
        }
    }

    companion object {
        /** Largest window one web_fetch call returns. */
        const val MAX_WINDOW_CHARS = 20_000
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")

        /** http:// for another host becomes https:// (cleartext is only allowed to this device). */
        fun upgradeUrl(url: String): String {
            if (!url.startsWith("http://", ignoreCase = true)) return url
            val host = url.substring(7).substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').let { h -> if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':') }
                .lowercase()
            return if (host in LOCAL_HOSTS) url else "https://" + url.substring(7)
        }

        private val DROP_BLOCKS = Regex(
            "<(script|style|noscript|svg|head|template)[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val BLOCK_TAGS = Regex(
            "</?(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|nav|pre|blockquote)[^>]*>",
            RegexOption.IGNORE_CASE
        )
        private val LINKS = Regex("<a\\s[^>]*href=\"(http[^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAGS = Regex("<[^>]+>")
        private val SPACES = Regex("[ \\t\\x0B\\f\\r]+")
        private val BLANK_LINES = Regex("\\n\\s*\\n(\\s*\\n)+")

        fun htmlToText(html: String): String {
            var s = html
            s = COMMENTS.replace(s, " ")
            s = DROP_BLOCKS.replace(s, " ")
            s = LINKS.replace(s) { m ->
                val label = TAGS.replace(m.groupValues[2], "").trim()
                if (label.isEmpty()) " " else "$label (${m.groupValues[1]})"
            }
            s = BLOCK_TAGS.replace(s, "\n")
            s = TAGS.replace(s, " ")
            s = decodeEntities(s)
            s = SPACES.replace(s, " ")
            s = s.lines().joinToString("\n") { it.trim() }
            s = BLANK_LINES.replace(s, "\n\n")
            return s.trim()
        }

        private fun decodeEntities(s: String): String = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d{1,6});")) { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace("&amp;", "&")
    }
}
