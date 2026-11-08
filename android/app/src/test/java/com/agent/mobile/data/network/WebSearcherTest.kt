package com.agent.mobile.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebSearcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val fixture = """
<html><body><div id="links" class="results">
<div class="result results_links results_links_deep result--ad ">
  <div class="links_main links_deep result__body">
    <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=x">Sponsored thing</a></h2>
    <a class="result__snippet" href="#">Buy now</a>
  </div>
</div>
<div class="result results_links results_links_deep web-result ">
  <div class="links_main links_deep result__body">
    <h2 class="result__title">
      <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwiki.termux.com%2Fwiki%2FTermux%3AAPI&amp;rut=abc">Termux:API - <b>Termux</b> Wiki</a>
    </h2>
    <div class="result__extras"><div class="result__extras__url"><a class="result__url" href="//duckduckgo.com/l/?uddg=x">wiki.termux.com</a></div></div>
    <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Exposes device functionality like <b>battery</b> status &amp; SMS.</a>
  </div>
</div>
<div class="result results_links results_links_deep web-result ">
  <div class="links_main links_deep result__body">
    <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://github.com/termux/termux-api">termux/termux-api</a></h2>
    <a class="result__snippet" href="https://github.com/termux/termux-api">Termux add-on app which exposes device functionality as API.</a>
  </div>
</div>
</div></body></html>
"""

    @Test
    fun parsesOrganicResultsAndSkipsAds() {
        val hits = WebSearcher.parseDuckDuckGoHtml(fixture)
        assertEquals(2, hits.size)
        assertEquals("Termux:API - Termux Wiki", hits[0].title)
        assertEquals("https://wiki.termux.com/wiki/Termux:API", hits[0].url)
        assertEquals("Exposes device functionality like battery status & SMS.", hits[0].snippet)
        assertEquals("https://github.com/termux/termux-api", hits[1].url)
    }

    @Test
    fun resolvesRedirectLinks() {
        assertEquals("https://example.com/a?b=1", WebSearcher.resolveUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&rut=x"))
        assertEquals("https://example.com/", WebSearcher.resolveUrl("https://example.com/"))
        assertNull(WebSearcher.resolveUrl("javascript:alert(1)"))
    }

    @Test
    fun searchPostsQueryAndFormatsResults() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(fixture))
        val searcher = WebSearcher(endpoint = server.url("/html/").toString())
        val result = searcher.search("termux battery", maxResults = 1)
        assertTrue(result.ok)
        assertEquals(1, result.hits.size)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val form = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
        assertTrue(form, form.contains("q=termux battery"))
        val text = WebSearcher.format("termux battery", result.hits)
        assertTrue(text.contains("1. Termux:API - Termux Wiki"))
        assertTrue(text.contains("web_fetch"))
    }

    @Test
    fun captchaPageIsReportedAsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><form id=\"challenge-form\">anomaly detected</form></html>"))
        val result = WebSearcher(endpoint = server.url("/html/").toString()).search("x")
        assertFalse(result.ok)
        assertTrue(result.error.contains("captcha"))
    }

    @Test
    fun emptyQueryIsRejected() = runBlocking {
        assertFalse(WebSearcher(endpoint = server.url("/").toString()).search("  ").ok)
        assertEquals(0, server.requestCount)
    }
}
