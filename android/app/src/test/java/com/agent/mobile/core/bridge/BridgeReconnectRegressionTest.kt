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
        try {
            server?.shutdown()
        } catch (e: java.io.IOException) {
            // Open WebSockets can delay MockWebServer's shutdown; the assertions already ran.
        }
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
