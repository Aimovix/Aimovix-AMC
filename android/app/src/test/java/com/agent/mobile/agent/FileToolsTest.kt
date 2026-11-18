package com.agent.mobile.agent

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.network.WebFetcher
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.testutil.FakeBridgeDaemon
import com.agent.mobile.testutil.ScriptedLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/** #14: large files and pages are readable in windows; edit_file changes exactly one place. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var llm: ScriptedLlm
    private lateinit var daemon: FakeBridgeDaemon
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var bridge: TermuxBridgeClient
    private lateinit var engine: AutonomousAgentEngine

    @Before
    fun setUp() = runBlocking {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        llm = ScriptedLlm().start()
        daemon = FakeBridgeDaemon().start()
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
        bridge = TermuxBridgeClient(host = daemon.host, port = daemon.port, token = "test-token")
        bridge.connect()
        assertTrue(bridge.awaitConnected(5_000L))
        engine = AutonomousAgentEngine(bridgeClient = bridge, memoryStore = MemoryStore(tmp.newFile()), headless = true)
        engine.setModelConfig(ModelConfig(ProviderType.OPENAI, "m", "k", llm.baseUrl))
        engine.setSecurityPreset(SecurityPreset.AUTONOMOUS)
    }

    @After
    fun tearDown() {
        engine.closeAndAwait()
        bridge.disconnect()
        daemon.shutdown()
        llm.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun aFiftyThousandCharacterFileIsReadableInWindows() {
        val text = (1..1000).joinToString("\n") { "line $it " + "x".repeat(40) }
        assertTrue(text.length > 45_000)
        val first = FileWindow.render(text, null, null)
        // 300 lines would exceed the character cap: the window ends earlier, at a line boundary.
        val next = Regex("""next window: offset=(\d+)""").find(first)!!.groupValues[1].toInt()
        assertTrue(first.startsWith("[lines 1–${next - 1} of 1000"))
        assertTrue(next in 200..301)
        assertTrue(first.contains("line ${next - 1} x"))
        assertFalse(first.contains("line $next x"))
        val middle = FileWindow.render(text, "501", "100")
        assertTrue(middle.contains("line 501 x"))
        assertTrue(middle.contains("line 600 x"))
        assertFalse(middle.contains("line 601 x"))
        val last = FileWindow.render(text, "990", null)
        assertTrue(last.contains("line 1000 x"))
        assertTrue(last.contains("end of file"))
        assertEquals("small", FileWindow.render("small", null, null))
        // A window never exceeds the character cap, whatever the line count asked for.
        val wide = FileWindow.render((1..500).joinToString("\n") { "y".repeat(200) }, "1", "2000")
        assertTrue(wide.length < FileWindow.MAX_WINDOW_CHARS + 200)
    }

    @Test
    fun editFileReplacesOnlyTheTargetAndRefusesAmbiguousEdits() = runBlocking {
        daemon.files["~/app.py"] = "a = 1\nb = 2\nc = 3\nb = 2\n"
        llm.tool("edit_file", JSONObject().put("path", "~/app.py").put("old_text", "a = 1").put("new_text", "a = 10"))
        llm.step { body ->
            assertTrue(ScriptedLlm.lastToolResult(body).contains("replaced 1 occurrence"))
            ScriptedLlm.sse(ScriptedLlm.toolChunk("e2", "edit_file", JSONObject().put("path", "~/app.py").put("old_text", "b = 2").put("new_text", "b = 20")))
        }
        llm.step { body ->
            assertTrue(ScriptedLlm.lastToolResult(body).contains("occurs 2 times"))
            ScriptedLlm.sse(ScriptedLlm.toolChunk("e3", "edit_file", JSONObject().put("path", "~/app.py").put("old_text", "zzz").put("new_text", "q")))
        }
        llm.step { body ->
            assertTrue(ScriptedLlm.lastToolResult(body).contains("was not found"))
            ScriptedLlm.sse(ScriptedLlm.textChunk("Done."))
        }
        engine.runHeadless("t", "fix a", RunMode.UNATTENDED)
        assertEquals("a = 10\nb = 2\nc = 3\nb = 2\n", daemon.files["~/app.py"])
    }

    @Test
    fun webPagesComeInCharacterWindows() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val body = (1..3000).joinToString(" ") { "word$it" }
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(body)) }
            val first = WebFetcher().fetch(server.url("/p").toString())
            assertTrue(first.text.contains("next window: start=12000"))
            assertTrue(first.text.contains("word1 "))
            val second = WebFetcher().fetch(server.url("/p").toString(), start = 12_000, length = 5_000)
            assertTrue(second.text.contains("characters 12001–17000"))
            assertFalse(second.text.contains("word1 word2"))
        } finally {
            server.shutdown()
        }
    }

    /** #13: the agent starts long work in the background and checks on it later. */
    @Test
    fun backgroundJobsAreStartedAndPolledThroughTools() = runBlocking {
        llm.tool("job_start", JSONObject().put("command", "python -m http.server 8080").put("name", "web"))
        llm.step { body ->
            val started = ScriptedLlm.lastToolResult(body)
            assertTrue(started, started.contains("Started background job j1"))
            ScriptedLlm.sse(ScriptedLlm.toolChunk("s1", "job_status", JSONObject().put("job_id", "j1")))
        }
        llm.step { body ->
            assertTrue(ScriptedLlm.lastToolResult(body).contains("server ready on :8080"))
            ScriptedLlm.sse(ScriptedLlm.textChunk("Server runs."))
        }
        val result = engine.runHeadless("t", "start the web server", RunMode.UNATTENDED)
        assertEquals("Server runs.", result.summary)
        assertEquals("python -m http.server 8080", daemon.jobs["j1"])
    }
}
