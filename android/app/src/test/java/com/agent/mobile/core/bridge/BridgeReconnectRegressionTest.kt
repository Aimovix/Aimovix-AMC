package com.agent.mobile.core.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

/**
 * Regression tests for the bridge staying in "Connecting" forever: callers set the status to
 * Connecting before connecting, and a failed socket was never cleared, so every later attempt
 * was skipped as "already in progress".
 */
@RunWith(RobolectricTestRunner::class)
class BridgeReconnectRegressionTest {

    private lateinit var context: Context
    private var server: MockWebServer? = null
    private val clients = mutableListOf<TermuxBridgeClient>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        clients.forEach { it.disconnect() }
        // Open WebSockets can keep MockWebServer's shutdown waiting; the assertions already ran,
        // so it gets a few seconds and is then left to the daemon thread.
        val s = server ?: return
        Thread {
            try {
                s.shutdown()
            } catch (e: java.io.IOException) {
                // see above
            }
        }.apply { isDaemon = true; start(); join(5_000) }
    }

    private fun authServer(vararg acceptTokens: String) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            if (json.optString("action") == "auth") {
                if (json.optString("token") in acceptTokens) {
                    webSocket.send(JSONObject().put("type", "auth_ok").put("cwd", "~").toString())
                } else {
                    webSocket.send(JSONObject().put("type", "auth_fail").put("error", "invalid token").toString())
                    webSocket.close(1008, "Authentication required")
                }
            }
        }
    }

    private fun client(port: Int, token: String) =
        TermuxBridgeClient(host = "127.0.0.1", port = port, token = token, context = context).also { clients += it }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun connectsWhenTheBridgeStartsAfterTheApp() = runBlocking {
        val port = freePort()
        val c = client(port, "good")
        c.connect()
        // Nothing listens yet: the attempt fails with a readable error.
        val failed = withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Error } }
        assertNotNull(failed)
        assertTrue((failed as ConnectionStatus.Error).message.contains("not running"))

        // The bridge starts, the user taps "Connect now" right away.
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(authServer("good")))
            start(port)
        }
        c.connect("good")
        val connected = withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } }
        assertNotNull("Client must not stay stuck in Connecting (was ${c.connectionStatus.value})", connected)
    }

    /** Accepts "good", streams "step 1 done" for every command and then optionally reports a daemon timeout. */
    private fun slowCommandServer(daemonTimeout: Boolean) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            when (json.optString("action")) {
                "auth" -> webSocket.send(JSONObject().put("type", "auth_ok").put("cwd", "~").toString())
                "execute" -> {
                    val id = json.optString("execution_id")
                    webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", "step 1 done\n").toString())
                    if (daemonTimeout) {
                        webSocket.send(
                            JSONObject().put("type", "error").put("execution_id", id)
                                .put("error", "Command timed out and was stopped.").toString()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aTimedOutCommandKeepsTheOutputItPrinted() = runBlocking {
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(slowCommandServer(daemonTimeout = false)))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })

        val result = c.executeCommand("make all", timeoutMs = 1_500)
        assertEquals(124, result.exitCode)
        assertTrue(result.isError)
        assertTrue("Output before the timeout is kept (was '${result.stdout}')", result.stdout.contains("step 1 done"))
        assertTrue(result.stderr.contains("Timeout"))
    }

    @Test
    fun aDaemonTimeoutErrorKeepsTheOutputItPrinted() = runBlocking {
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(slowCommandServer(daemonTimeout = true)))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })

        val result = c.executeCommand("make all", timeoutMs = 10_000)
        assertEquals(124, result.exitCode)
        assertTrue(result.stdout.contains("step 1 done"))
        assertTrue(result.stderr.contains("Command timed out and was stopped."))
    }

    @Test
    fun aFileRequestWithoutAnswerFailsAsIoErrorNotAsCancellation() = runBlocking {
        // The daemon accepts the connection but never answers read_file (Termux frozen by the system).
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(authServer("good")))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })

        val error = try {
            c.readFile("~/notes.txt", timeoutMs = 300)
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            "A timeout must be a normal I/O error, not a cancellation of the agent run (was $error)",
            error is java.io.IOException && error !is kotlinx.coroutines.CancellationException
        )
        assertTrue(error!!.message!!.contains("did not answer read_file"))
    }

    /** First connection: streams part 1 of a command, then drops the socket. */
    private fun droppingServer(seenId: java.util.concurrent.atomic.AtomicReference<String>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            when (json.optString("action")) {
                "auth" -> webSocket.send(JSONObject().put("type", "auth_ok").put("cwd", "~").toString())
                "execute" -> {
                    val id = json.optString("execution_id")
                    seenId.set(id)
                    webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", "part1 ").toString())
                    webSocket.close(1001, "going away")
                }
            }
        }
    }

    /** Second connection: hands the session back when the client names the execution (or not, like an old bridge). */
    private fun resumingServer(seenId: java.util.concurrent.atomic.AtomicReference<String>, newBridge: Boolean, authIds: MutableList<String>) =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("action") != "auth") return
                val ids = json.optJSONArray("execution_ids")
                if (ids != null) for (i in 0 until ids.length()) authIds += ids.getString(i)
                val id = seenId.get()
                val ok = JSONObject().put("type", "auth_ok").put("cwd", "~")
                if (newBridge) ok.put("resumed_executions", org.json.JSONArray().put(id))
                webSocket.send(ok.toString())
                if (newBridge) {
                    webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", "part2").toString())
                    webSocket.send(JSONObject().put("type", "completed").put("execution_id", id).put("exit_code", 0).toString())
                }
            }
        }

    @Test
    fun aCommandSurvivesAShortDisconnectAndReportsOnce() = runBlocking {
        val seenId = java.util.concurrent.atomic.AtomicReference("")
        val authIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(droppingServer(seenId)))
            enqueue(MockResponse().withWebSocketUpgrade(resumingServer(seenId, newBridge = true, authIds = authIds)))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })

        val result = c.executeCommand("pip install requests", timeoutMs = 20_000)
        assertEquals("The real result arrives after the reconnect", 0, result.exitCode)
        assertFalse(result.isError)
        assertEquals("part1 part2", result.stdout)
        assertEquals("The client named its running command at login", listOf(seenId.get()), authIds.toList())
    }

    @Test
    fun withAnOldBridgeTheResultIsReportedAsUnknownNotAsFailure() = runBlocking {
        val seenId = java.util.concurrent.atomic.AtomicReference("")
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(droppingServer(seenId)))
            enqueue(MockResponse().withWebSocketUpgrade(resumingServer(seenId, newBridge = false, authIds = mutableListOf())))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })

        val result = c.executeCommand("pip install requests", timeoutMs = 20_000)
        assertTrue(result.isError)
        assertTrue(result.stderr, result.stderr.contains("result is unknown"))
    }

    @Test
    fun theWorkingFolderIsSentWithEveryCommand() = runBlocking {
        val sentCwds = java.util.concurrent.CopyOnWriteArrayList<String>()
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    when (json.optString("action")) {
                        "auth" -> webSocket.send(JSONObject().put("type", "auth_ok").put("cwd", "/home").toString())
                        "execute" -> {
                            sentCwds += json.optString("cwd", "<none>")
                            val cwd = if (json.optString("command").startsWith("cd ")) "/home/proj" else json.optString("cwd", "/home")
                            webSocket.send(
                                JSONObject().put("type", "completed").put("execution_id", json.optString("execution_id"))
                                    .put("exit_code", 0).put("cwd", cwd).toString()
                            )
                        }
                    }
                }
            }))
            start()
        }
        val c = client(server!!.port, "good")
        c.connect("good")
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })
        c.executeCommand("cd proj", timeoutMs = 5_000)
        assertEquals("/home/proj", c.workingDir)
        c.executeCommand("ls", timeoutMs = 5_000)
        assertEquals(listOf("<none>", "/home/proj"), sentCwds.toList())
    }

    @Test
    fun reconnectIfDisconnectedRecoversAfterAFailure() = runBlocking {
        val port = freePort()
        val c = client(port, "good")
        c.connect()
        withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Error } }
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(authServer("good")))
            start(port)
        }
        // Same path as MainActivity.onResume / BridgeStarter.
        c.reconnectIfDisconnected(force = true)
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } })
    }

    @Test
    fun aCorrectTokenWorksAfterAWrongOne() = runBlocking {
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(authServer("good")))
            enqueue(MockResponse().withWebSocketUpgrade(authServer("good")))
            start()
        }
        val c = client(server!!.port, "wrong")
        c.connect()
        assertNotNull(withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.AuthFailed } })
        c.connect("good")
        val connected = withTimeoutOrNull(5_000) { c.connectionStatus.first { it is ConnectionStatus.Connected } }
        assertNotNull("A corrected token must connect (was ${c.connectionStatus.value})", connected)
    }

    @Test
    fun silentBridgeEndsInAnErrorInsteadOfConnectingForever() = runBlocking {
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            start()
        }
        val c = client(server!!.port, "token")
        c.connect()
        val status = withTimeoutOrNull(TermuxBridgeClient.AUTH_TIMEOUT_MS + 4_000) {
            c.connectionStatus.first { it is ConnectionStatus.Error }
        }
        assertNotNull("Auth watchdog must fire (was ${c.connectionStatus.value})", status)
        assertTrue((status as ConnectionStatus.Error).message.contains("did not answer"))
    }
}
