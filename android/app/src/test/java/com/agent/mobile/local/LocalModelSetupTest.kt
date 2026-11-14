package com.agent.mobile.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import com.agent.mobile.data.network.LlmActionResult
import com.agent.mobile.service.scheduler.LocalModelSetupWorker
import com.agent.mobile.ui.settings.components.LocalCardView
import com.agent.mobile.ui.settings.components.localCardView
import com.agent.mobile.ui.settings.components.localSetupButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** The one-tap local AI setup: phases, progress, errors with one fix, and resuming. */
@RunWith(RobolectricTestRunner::class)
class LocalModelSetupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LocalSetupStore

    private val big = CatalogModel("big", "Big 4B", "big.gguf", "https://huggingface.co/x/big.gguf", "", 2_600, 7_500, 8_192, "speed")
    private val small = CatalogModel("small", "Small 2B", "small.gguf", "https://huggingface.co/x/small.gguf", "", 1_400, 5_500, 8_192, "small")

    private fun phone(freeGb: Long = 64) = DeviceProfile(
        totalRamMb = 8 * 1024, availRamMb = 4 * 1024, freeStorageMb = freeGb * 1024,
        abis = listOf("arm64-v8a"), cpuFeatures = setOf("asimddp"), performanceCores = 4
    )

    /** Answers like the bridge: each llm_status call returns the next scripted status. */
    private class FakeBridge(
        override var connected: Boolean = true,
        override val capabilities: Set<String> = setOf("llm", LocalModelSetup.BACKGROUND_INSTALL_CAPABILITY)
    ) : LlmBridge {
        val calls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val statuses = ArrayDeque<Map<String, String>>()
        var installResult: (Int) -> LlmActionResult = { LlmActionResult(true, "", mapOf("install_phase" to "runtime", "install_model" to "big.gguf")) }
        var startOk = true

        override suspend fun awaitConnected(timeoutMs: Long) = connected

        override suspend fun action(action: String, params: Map<String, String>, timeoutMs: Long): LlmActionResult {
            calls += action to params
            return when (action) {
                "llm_install" -> installResult(calls.count { it.first == "llm_install" })
                "llm_status" -> LlmActionResult(true, "", statuses.removeFirstOrNull() ?: mapOf("install_phase" to "idle"))
                "llm_start" -> LlmActionResult(startOk, if (startOk) "" else "llama-server exited", emptyMap())
                else -> LlmActionResult(true, "", emptyMap())
            }
        }
    }

    private fun status(phase: String, model: String = "big.gguf", bytes: Long = 0, total: Long = 0, error: String = "") =
        mapOf("install_phase" to phase, "install_model" to model, "install_bytes" to "$bytes", "install_total" to "$total", "install_error" to error)

    private var now = 0L

    private fun setup(bridge: LlmBridge, profile: () -> DeviceProfile = { phone() }, passes: (Int) -> Boolean = { true }): LocalModelSetup {
        var runs = 0
        return LocalModelSetup(
            context, bridge, store, clock = { now.also { now += 1_000 } }, pollMs = 1,
            profiler = profile, catalogSource = { listOf(big, small) }
        ).also { s ->
            s.benchmarkRunner = {
                runs++
                if (passes(runs)) LocalModelSetup.Benchmark(14.0, 5, 5) else LocalModelSetup.Benchmark(4.0, 5, 5)
            }
        }
    }

    @Before
    fun setUp() {
        store = LocalSetupStore(context.getSharedPreferences("test_local_setup", Context.MODE_PRIVATE))
        store.save(LocalSetupState())
    }

    @Test
    fun oneTapRunsAllPhasesAndReportsDownloadProgress() = runBlocking {
        val bridge = FakeBridge()
        bridge.statuses += status("download", bytes = 500, total = 1_000)
        bridge.statuses += status("download", bytes = 750, total = 1_000)
        bridge.statuses += status("verify")
        bridge.statuses += status("done")
        val seen = CopyOnWriteArrayList<SetupProgress>()

        val outcome = setup(bridge).run { seen += it }

        assertTrue(outcome is LocalModelSetup.Outcome.Ready)
        assertEquals(big, (outcome as LocalModelSetup.Outcome.Ready).model)
        val phases = seen.map { it.phase }.distinct()
        assertEquals(
            listOf(SetupPhase.CHECK, SetupPhase.DOWNLOAD, SetupPhase.VERIFY, SetupPhase.START, SetupPhase.BENCHMARK, SetupPhase.DONE),
            phases
        )
        val downloads = seen.filter { it.phase == SetupPhase.DOWNLOAD && it.total > 0 }
        assertEquals(0.5f, downloads.first().fraction!!, 0.001f)
        assertEquals(0.75f, downloads.last().fraction!!, 0.001f)
        assertTrue("speed is known after two samples", downloads.last().bytesPerSecond > 0)
        val install = bridge.calls.first { it.first == "llm_install" }.second
        assertEquals("big.gguf", install["model"])
        assertEquals((2_600L * 1024 * 1024).toString(), install["size"])
        assertEquals(SetupPhase.DONE, store.load().phase)
    }

    @Test
    fun anInterruptedSetupContinuesWithTheSavedModelWithoutCheckingAgain() = runBlocking {
        store.save(LocalSetupState(phase = SetupPhase.DOWNLOAD, modelId = "small", ctx = 4_096, threads = 4))
        val bridge = FakeBridge()
        bridge.installResult = { LlmActionResult(true, "", status("download", model = "small.gguf", bytes = 10, total = 100)) }
        bridge.statuses += status("done", model = "small.gguf")

        val outcome = setup(bridge, profile = { error("the phone is not checked again") }).run { }

        assertEquals(small, (outcome as LocalModelSetup.Outcome.Ready).model)
        assertEquals(4_096, outcome.ctx)
        assertEquals("small.gguf", bridge.calls.first { it.first == "llm_install" }.second["model"])
    }

    @Test
    fun aDownloadThatDiedInTermuxIsStartedAgainAndResumes() = runBlocking {
        val bridge = FakeBridge()
        bridge.statuses += status("download", bytes = 10, total = 100)
        bridge.statuses += status("interrupted", bytes = 40, total = 100)
        bridge.statuses += status("done")
        val outcome = setup(bridge).run { }
        assertTrue(outcome is LocalModelSetup.Outcome.Ready)
        assertEquals(2, bridge.calls.count { it.first == "llm_install" })
    }

    @Test
    fun errorsBecomeOneFixEach() = runBlocking {
        val offline = FakeBridge(connected = false)
        assertEquals(SetupError.BRIDGE, (setup(offline).run { } as LocalModelSetup.Outcome.Failed).error)
        assertTrue(store.load().resumable.not())

        val old = FakeBridge(capabilities = setOf("llm"))
        assertEquals(SetupError.BRIDGE_OLD, (setup(old).run { } as LocalModelSetup.Outcome.Failed).error)

        store.save(LocalSetupState())
        val full = setup(FakeBridge(), profile = { phone(freeGb = 1) }).run { } as LocalModelSetup.Outcome.Failed
        assertEquals(SetupError.STORAGE, full.error)
        assertTrue(full.reason.contains("free storage"))

        store.save(LocalSetupState())
        val damaged = FakeBridge().also { it.statuses += status("error", error = "checksum") }
        assertEquals(SetupError.CHECKSUM, (setup(damaged).run { } as LocalModelSetup.Outcome.Failed).error)

        store.save(LocalSetupState())
        val noNet = FakeBridge().also { it.statuses += status("error", error = "network") }
        val failed = setup(noNet).run { } as LocalModelSetup.Outcome.Failed
        assertEquals(SetupError.NETWORK, failed.error)
        assertTrue("a network failure keeps the download resumable", store.load().resumable)
        assertTrue(SetupError.NETWORK.retryable)
        assertFalse(SetupError.CHECKSUM.retryable)

        store.save(LocalSetupState())
        val noStart = FakeBridge().also { it.statuses += status("done"); it.startOk = false }
        assertEquals(SetupError.START, (setup(noStart).run { } as LocalModelSetup.Outcome.Failed).error)
    }

    @Test
    fun tooSlowStepsDownThenGivesUp() = runBlocking {
        val bridge = FakeBridge()
        bridge.installResult = { n ->
            val file = if (n == 1) "big.gguf" else "small.gguf"
            LlmActionResult(true, "", status("done", model = file))
        }
        val outcome = setup(bridge, passes = { false }).run { } as LocalModelSetup.Outcome.Failed
        assertEquals(SetupError.TOO_SLOW, outcome.error)
        assertEquals(listOf("big.gguf", "small.gguf"), bridge.calls.filter { it.first == "llm_install" }.map { it.second["model"] })
    }

    @Test
    fun cancelStopsTheDownloadAndKeepsItResumable() = runBlocking {
        store.save(LocalSetupState(phase = SetupPhase.DOWNLOAD, modelId = "big"))
        val bridge = FakeBridge()
        setup(bridge).cancel()
        assertEquals("llm_cancel", bridge.calls.single().first)
        assertEquals(SetupPhase.CANCELLED, store.load().phase)
        assertTrue(store.load().resumable)
        assertEquals("Resume setup", localSetupButton(store.load()))
    }

    @Test
    fun meterSmoothsSpeedAndEstimatesTimeLeft() {
        val m = ProgressMeter()
        assertEquals(-1L, m.etaSeconds(0, 100))
        m.update(0, 0)
        m.update(1_000_000, 1_000)
        assertEquals(1_000_000.0, m.bytesPerSecond, 0.1)
        assertEquals(9L, m.etaSeconds(1_000_000, 10_000_000))
        m.update(1_500_000, 2_000)
        assertTrue(m.bytesPerSecond in 500_000.0..1_000_000.0)
    }

    @Test
    fun stateSurvivesAsJsonAndTheCardPicksTheRightView() {
        val state = LocalSetupState(SetupPhase.FAILED, "big", 8_192, 4, SetupError.NETWORK, "stopped", 5)
        assertEquals(state, LocalSetupState.fromJson(state.toJson()))
        assertEquals(LocalSetupState(), LocalSetupState.fromJson("garbage"))

        val progress = SetupProgress(SetupPhase.DOWNLOAD, "Downloading", "Big", 1, 4, 2, 3)
        assertEquals(progress, LocalModelSetupWorker.fromData(LocalModelSetupWorker.toData(progress)))
        assertEquals("0.0 / 0.0 GB · 0.0 MB/s · <1 min left", LocalModelSetupWorker.detailLine(progress).replace(',', '.'))

        assertEquals(LocalCardView.Running(progress), localCardView(WorkInfo.State.RUNNING, progress, LocalSetupState()))
        assertEquals(LocalCardView.Waiting, localCardView(WorkInfo.State.ENQUEUED, null, state))
        assertEquals(LocalCardView.Idle(state), localCardView(WorkInfo.State.SUCCEEDED, null, state))
        assertEquals("Set up local AI", localSetupButton(LocalSetupState()))
        assertEquals("Set up again", localSetupButton(LocalSetupState(phase = SetupPhase.DONE, modelId = "big")))
    }
}
