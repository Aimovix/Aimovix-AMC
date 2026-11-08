package com.agent.mobile.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebFetcherTest {
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

    @Test
    fun htmlIsConvertedToText() {
        val text = WebFetcher.htmlToText(
            "<html><head><title>x</title><style>.a{}</style></head><body><h1>Hello</h1>" +
                "<script>alert(1)</script><p>Tom &amp; Jerry&nbsp;&#169;</p><a href=\"https://e.com\">Link</a></body></html>"
        )
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("Tom & Jerry ©"))
        assertTrue(text.contains("Link (https://e.com)"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains(".a{}"))
    }

    @Test
    fun fetchReturnsReadableBody() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>Weather: sunny</p>"))
        val result = WebFetcher().fetch(server.url("/w").toString())
        assertTrue(result.ok)
        assertTrue(result.text.contains("Weather: sunny"))
    }

    @Test
    fun httpErrorsAreReported() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val result = WebFetcher().fetch(server.url("/x").toString())
        assertFalse(result.ok)
        assertEquals(404, result.statusCode)
    }

    @Test
    fun nonHttpUrlsAreRejected() = runTest {
        assertFalse(WebFetcher().fetch("file:///etc/passwd").ok)
    }
}
