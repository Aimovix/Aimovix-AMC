package com.agent.mobile.agent

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/** A chat run reports whether it ended normally, by Stop, or because the app went away (#7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InteractiveRunListenerTest {

    private lateinit var server: MockWebServer
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    private class Recorder : InteractiveRunListener {
        val started = CompletableDeferred<String>()
        val ended = CompletableDeferred<Boolean>()
        override suspend fun onRunStarted(sessionId: String, title: String) { started.complete(sessionId) }
        override suspend fun onRunEnded(sessionId: String, interrupted: Boolean) { ended.complete(interrupted) }
    }

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun engine(rec: Recorder) = AutonomousAgentEngine(bridgeClient = TermuxBridgeClient(token = "offline")).also {
        it.setModelConfig(ModelConfig(provider = ProviderType.OPENAI, modelName = "m", apiKey = "k", baseUrl = server.url("/v1/chat/completions").toString()))
        it.interactiveRunListener = rec
    }

    private fun hangingProvider() = server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    @Test
    fun closingTheAppMidRunReportsAnInterruptedRun() = runBlocking {
        hangingProvider()
        val rec = Recorder()
        val e = engine(rec)
        withTimeout(5_000) { e.isInitialized.first { it } }
        assertTrue(e.startTask("back up my photos"))
        withTimeout(5_000) { rec.started.await() }
        e.close()
        assertTrue("Closing the app is an interruption", withTimeout(5_000) { rec.ended.await() })
    }

    @Test
    fun theStopButtonIsNoInterruption() = runBlocking {
        hangingProvider()
        val rec = Recorder()
        val e = engine(rec)
        withTimeout(5_000) { e.isInitialized.first { it } }
        e.startTask("back up my photos")
        withTimeout(5_000) { rec.started.await() }
        e.emergencyStop()
        assertFalse(withTimeout(10_000) { rec.ended.await() })
        e.close()
    }

    @Test
    fun aFinishedRunIsNoInterruption() = runBlocking {
        val chunk = """{"choices":[{"delta":{"content":"Done."}}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
        val rec = Recorder()
        val e = engine(rec)
        withTimeout(5_000) { e.isInitialized.first { it } }
        e.startTask("hello")
        assertEquals(false, withTimeout(10_000) { rec.ended.await() })
        e.close()
    }
}
