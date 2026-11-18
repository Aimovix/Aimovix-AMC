package com.agent.mobile.core.bridge

import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.testutil.FakeBridgeDaemon
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #15 reconnect without double execution, #26 working folder, #13 background jobs (client side). */
@RunWith(RobolectricTestRunner::class)
class BridgeContinuityTest {

    private lateinit var daemon: FakeBridgeDaemon
    private lateinit var client: TermuxBridgeClient

    @Before
    fun setUp() = runBlocking {
        daemon = FakeBridgeDaemon().start()
        client = TermuxBridgeClient(host = daemon.host, port = daemon.port, token = "test-token")
        client.connect()
        assertTrue(client.awaitConnected(5_000L))
    }

    @After
    fun tearDown() {
        client.disconnect()
        daemon.shutdown()
    }

    @Test
    fun aDroppedConnectionDeliversTheRealResultExactlyOnce() = runBlocking {
        daemon.onExecute = { cmd -> if (cmd.startsWith("pip install")) null else "ok\n" to 0 }
        val run = async { client.executeCommand("pip install requests", timeoutMs = 20_000L) }
        withTimeout(5_000) { while (daemon.running.isEmpty()) delay(20) }
        daemon.dropConnections()
        val result = withTimeout(15_000) { run.await() }
        assertFalse("The command was followed after the reconnect: ${result.stderr}", result.isError)
        assertTrue(result.stdout.contains("finished after reconnect"))
        assertEquals("Never executed twice", 1, daemon.executed().count { it.startsWith("pip install") })
        val auth = daemon.received.last { it.optString("action") == "auth" }
        assertEquals("own", auth.optString("claim"))
    }

    @Test
    fun withoutTheSessionBackTheResultIsUnknownNotRepeated() = runBlocking {
        daemon.onExecute = { cmd -> if (cmd.startsWith("make")) null else "ok\n" to 0 }
        daemon.finishOnReattach = null
        client.reconnectGraceMs = 1_500L
        val run = async { client.executeCommand("make all", timeoutMs = 20_000L) }
        withTimeout(5_000) { while (daemon.running.isEmpty()) delay(20) }
        daemon.running.clear() // the daemon restarted: nobody can give the session back
        daemon.dropConnections()
        val result = withTimeout(15_000) { run.await() }
        assertTrue(result.isError)
        assertTrue(result.stderr.contains("Its result is unknown"))
        assertEquals(1, daemon.executed().count { it.startsWith("make") })
    }

    @Test
    fun theWorkingFolderSurvivesAReconnect() = runBlocking {
        assertEquals(0, client.executeCommand("cd /home/projekt").exitCode)
        assertEquals("/home/projekt", client.workingDirectory)
        daemon.dropConnections()
        withTimeout(10_000) { while (client.connectionStatus.value !is com.agent.mobile.data.model.ConnectionStatus.Connected || daemon.connections.get() < 2) delay(50) }
        client.executeCommand("ls")
        val ls = daemon.received.last { it.optString("action") == "execute" && it.optString("command") == "ls" }
        assertEquals("/home/projekt", ls.optString("cwd"))
    }

    @Test
    fun backgroundJobsStartAndReport() = runBlocking {
        val started = client.jobAction("job_start", mapOf("command" to "python -m http.server", "name" to "web"))
        assertTrue(started.optBoolean("ok"))
        val id = started.getJSONObject("job").getString("job_id")
        val status = client.jobAction("job_status", mapOf("job_id" to id))
        assertEquals("done", status.getJSONObject("job").getString("state"))
        assertTrue(status.getJSONObject("job").getString("new_output").contains("server ready"))
        assertFalse(client.jobAction("job_status", mapOf("job_id" to "nope")).optBoolean("ok"))
    }
}
