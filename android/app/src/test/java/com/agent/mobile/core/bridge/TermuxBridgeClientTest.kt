package com.agent.mobile.core.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TermuxBridgeClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        context = ApplicationProvider.getApplicationContext()
        TermuxBridgeClient.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        TermuxBridgeClient.resetInstanceForTesting()
        try {
            mockServer.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }

    @Test
    fun testSuccessfulAuthHandshake() = runBlocking {
        val serverReceivedMessages = LinkedBlockingQueue<String>()

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedMessages.offer(text)
                val json = JSONObject(text)
                if (json.optString("action") == "auth") {
                    val resp = JSONObject().apply {
                        put("type", "auth_ok")
                        put("cwd", "/data/data/com.termux/files/home")
                        put("system", JSONObject().apply {
                            put("os", "Linux")
                            put("cwd", "/data/data/com.termux/files/home")
                            put("has_termux_api", true)
                            put("has_llama", false)
                            put("battery", JSONObject().apply {
                                put("percentage", 85)
                                put("plugged", "PLUGGED_AC")
                            })
                        })
                    }
                    webSocket.send(resp.toString())
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "secret-token-123",
            context = context
        )

        client.connect()

        val connected = client.awaitConnected(5000L)
        assertTrue("Client should successfully authenticate and connect", connected)

        val status = client.connectionStatus.value
        assertTrue("Status should be ConnectionStatus.Connected", status is ConnectionStatus.Connected)
        val info = (status as ConnectionStatus.Connected).info
        assertEquals("Linux", info.os)
        assertEquals("/data/data/com.termux/files/home", info.cwd)
        assertEquals(85, info.batteryPercentage)
        assertEquals(true, info.isCharging)
        assertTrue(info.hasTermuxApi)
        assertEquals(0, client.getReconnectAttempts())

        // Verify auth request was sent with token
        val received = serverReceivedMessages.poll(3, TimeUnit.SECONDS)
        assertNotNull(received)
        val authJson = JSONObject(received!!)
        assertEquals("auth", authJson.optString("action"))
        assertEquals("secret-token-123", authJson.optString("token"))

        client.disconnect()
    }

    @Test
    fun testFailedAuthAndPermanentBackoff() = runBlocking {
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("action") == "auth") {
                    val resp = JSONObject().apply {
                        put("type", "auth_fail")
                        put("error", "Authentication failed: invalid token.")
                    }
                    webSocket.send(resp.toString())
                    webSocket.close(1008, "Authentication required")
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "wrong-token",
            context = context
        )

        client.connect()

        val authFailedStatus = withTimeoutOrNull(5000L) {
            client.connectionStatus.first { it is ConnectionStatus.AuthFailed }
        }

        assertNotNull("Client must transition to AuthFailed status", authFailedStatus)
        assertTrue(authFailedStatus is ConnectionStatus.AuthFailed)
        assertEquals("Authentication failed: invalid token.", (authFailedStatus as ConnectionStatus.AuthFailed).error)

        // Ensure client remains in AuthFailed and does NOT schedule auto-reconnect
        kotlinx.coroutines.delay(2000L)
        assertTrue(client.connectionStatus.value is ConnectionStatus.AuthFailed)

        client.disconnect()
    }

    @Test
    fun testExponentialBackoffProgressionMath() {
        val client = TermuxBridgeClient(
            host = "127.0.0.1",
            port = 8765,
            token = "test",
            context = context
        )

        // Formula: min(1000 * 2^attempt, 30000) + jitter (0..1000)
        // Attempt 0: base 1000 -> [1000..2000]
        val delay0 = client.calculateBackoffDelay(0)
        assertTrue("Attempt 0 delay $delay0 should be in [1000, 2000]", delay0 in 1000L..2000L)

        // Attempt 1: base 2000 -> [2000..3000]
        val delay1 = client.calculateBackoffDelay(1)
        assertTrue("Attempt 1 delay $delay1 should be in [2000, 3000]", delay1 in 2000L..3000L)

        // Attempt 2: base 4000 -> [4000..5000]
        val delay2 = client.calculateBackoffDelay(2)
        assertTrue("Attempt 2 delay $delay2 should be in [4000, 5000]", delay2 in 4000L..5000L)

        // Attempt 3: base 8000 -> [8000..9000]
        val delay3 = client.calculateBackoffDelay(3)
        assertTrue("Attempt 3 delay $delay3 should be in [8000, 9000]", delay3 in 8000L..9000L)

        // Attempt 4: base 16000 -> [16000..17000]
        val delay4 = client.calculateBackoffDelay(4)
        assertTrue("Attempt 4 delay $delay4 should be in [16000, 17000]", delay4 in 16000L..17000L)

        // Attempt 5: base 32000 capped at 30000 -> [30000..31000]
        val delay5 = client.calculateBackoffDelay(5)
        assertTrue("Attempt 5 delay $delay5 should be in [30000, 31000]", delay5 in 30000L..31000L)

        // High attempts should remain strictly capped at MAX_BACKOFF_MS (30s) + jitter
        val delay10 = client.calculateBackoffDelay(10)
        assertTrue("Attempt 10 delay $delay10 should be in [30000, 31000]", delay10 in 30000L..31000L)
    }

    @Test
    fun testPingPongHeartbeatAndTimeout() = runBlocking {
        val serverReceivedPings = LinkedBlockingQueue<String>()

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("action")) {
                    "auth" -> {
                        webSocket.send(JSONObject().apply {
                            put("type", "auth_ok")
                            put("cwd", "/home")
                            put("system", JSONObject().apply { put("os", "Linux") })
                        }.toString())
                    }
                    "ping" -> {
                        serverReceivedPings.offer(text)
                        val pingId = json.optString("ping_id")
                        val ts = json.optLong("timestamp")
                        webSocket.send(JSONObject().apply {
                            put("type", "pong")
                            put("ping_id", pingId)
                            put("timestamp", ts)
                        }.toString())
                    }
                    "sys_info" -> {
                        webSocket.send(JSONObject().apply {
                            put("type", "sys_info")
                            put("cwd", "/home")
                            put("system", JSONObject().apply { put("os", "Linux") })
                        }.toString())
                    }
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "ping-token",
            context = context
        )

        client.connect()
        assertTrue(client.awaitConnected(5000L))

        // Trigger ping probe
        client.reconnectIfDisconnected(force = true)

        val pingMsg = serverReceivedPings.poll(5, TimeUnit.SECONDS)
        assertNotNull("Server should receive ping request", pingMsg)
        val pingJson = JSONObject(pingMsg!!)
        assertEquals("ping", pingJson.optString("action"))
        assertTrue(pingJson.has("ping_id"))

        // Status should remain connected
        assertTrue(client.connectionStatus.value is ConnectionStatus.Connected)

        client.disconnect()
    }

    @Test
    fun testCommandStreamingChunksAndCompletion() = runBlocking {
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("action")) {
                    "auth" -> {
                        webSocket.send(JSONObject().apply {
                            put("type", "auth_ok")
                            put("cwd", "/home")
                            put("system", JSONObject().apply { put("os", "Linux") })
                        }.toString())
                    }
                    "execute" -> {
                        val execId = json.optString("execution_id")
                        // Send stdout chunk 1
                        webSocket.send(JSONObject().apply {
                            put("type", "stdout")
                            put("execution_id", execId)
                            put("data", "line 1\n")
                        }.toString())
                        // Send stdout chunk 2
                        webSocket.send(JSONObject().apply {
                            put("type", "stdout")
                            put("execution_id", execId)
                            put("data", "line 2\n")
                        }.toString())
                        // Send stderr chunk
                        webSocket.send(JSONObject().apply {
                            put("type", "stderr")
                            put("execution_id", execId)
                            put("data", "warning notice\n")
                        }.toString())
                        // Send completed
                        webSocket.send(JSONObject().apply {
                            put("type", "completed")
                            put("execution_id", execId)
                            put("exit_code", 0)
                            put("cwd", "/home")
                        }.toString())
                    }
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "stream-token",
            context = context
        )

        client.connect()
        assertTrue(client.awaitConnected(5000L))

        val receivedChunks = mutableListOf<String>()
        val result = client.executeCommand("echo test", timeoutMs = 10_000L) { chunk ->
            receivedChunks.add(chunk)
        }

        assertEquals(0, result.exitCode)
        assertFalse(result.isError)
        assertEquals("line 1\nline 2\n", result.stdout)
        assertEquals("warning notice\n", result.stderr)
        assertEquals(3, receivedChunks.size)
        assertEquals("line 1\n", receivedChunks[0])
        assertEquals("line 2\n", receivedChunks[1])
        assertEquals("warning notice\n", receivedChunks[2])

        client.disconnect()
    }

    @Test
    fun testWriteFileProtocolSuccess() = runBlocking {
        val serverReceivedWrite = LinkedBlockingQueue<String>()

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("action")) {
                    "auth" -> {
                        webSocket.send(JSONObject().apply {
                            put("type", "auth_ok")
                            put("cwd", "/home")
                            put("system", JSONObject().apply { put("os", "Linux") })
                        }.toString())
                    }
                    "write_file" -> {
                        serverReceivedWrite.offer(text)
                        val reqId = json.optString("request_id")
                        val path = json.optString("path")
                        webSocket.send(JSONObject().apply {
                            put("type", "file_written")
                            put("path", path)
                            put("success", true)
                            put("request_id", reqId)
                        }.toString())
                    }
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "write-token",
            context = context
        )

        client.connect()
        assertTrue(client.awaitConnected(5000L))

        val success = client.writeFile("test.sh", "echo 'hello world'\n")
        assertTrue("writeFile should return true on success", success)

        val writeMsg = serverReceivedWrite.poll(5, TimeUnit.SECONDS)
        assertNotNull(writeMsg)
        val writeJson = JSONObject(writeMsg!!)
        assertEquals("write_file", writeJson.optString("action"))
        assertEquals("test.sh", writeJson.optString("path"))
        assertEquals("echo 'hello world'\n", writeJson.optString("content"))

        client.disconnect()
    }

    @Test
    fun testInterruptedProtocolHandling() = runBlocking {
        val serverReceivedInterrupt = LinkedBlockingQueue<String>()

        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("action")) {
                    "auth" -> {
                        webSocket.send(JSONObject().apply {
                            put("type", "auth_ok")
                            put("cwd", "/home")
                            put("system", JSONObject().apply { put("os", "Linux") })
                        }.toString())
                    }
                    "interrupt" -> {
                        serverReceivedInterrupt.offer(text)
                        val execId = json.optString("execution_id")
                        webSocket.send(JSONObject().apply {
                            put("type", "interrupted")
                            put("execution_id", execId)
                        }.toString())
                    }
                }
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "interrupt-token",
            context = context
        )

        client.connect()
        assertTrue(client.awaitConnected(5000L))

        client.interruptCurrent("exec-12345")

        val interruptMsg = serverReceivedInterrupt.poll(5, TimeUnit.SECONDS)
        assertNotNull(interruptMsg)
        val interruptJson = JSONObject(interruptMsg!!)
        assertEquals("interrupt", interruptJson.optString("action"))
        assertEquals("exec-12345", interruptJson.optString("execution_id"))

        client.disconnect()
    }

    @Test
    fun testBridgeSingletonDecoupling() {
        val client1 = TermuxBridgeClient.getInstance(context, "token-a")
        val client2 = TermuxBridgeClient.getInstance(context, "token-a")
        assertSame("Subsequent getInstance calls must return the same singleton instance", client1, client2)

        TermuxBridgeClient.resetInstanceForTesting()
        val client3 = TermuxBridgeClient.getInstance(context, "token-b")
        assertNotSame("After reset, a new singleton instance should be created", client1, client3)
    }

    @Test
    fun testReconnectRaceGuardDoesNotThrash() = runBlocking {
        // Enqueue upgrade that delays response to keep state in Connecting
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Do not respond immediately
            }
        }))

        val client = TermuxBridgeClient(
            host = mockServer.hostName,
            port = mockServer.port,
            token = "race-token",
            context = context
        )

        client.connect()
        // Immediate second call should be ignored by the race guard
        client.reconnectIfDisconnected(force = false)
        client.reconnectIfDisconnected(force = false)

        // Status should be Connecting without thrashing
        val status = client.connectionStatus.value
        assertTrue("Status should remain Connecting without crashing", status is ConnectionStatus.Connecting || status is ConnectionStatus.Connected)

        client.disconnect()
    }
}
