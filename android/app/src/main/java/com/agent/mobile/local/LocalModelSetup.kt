package com.agent.mobile.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Reads what [LocalModelCatalog.recommend] needs from the phone. */
object DeviceProfiler {
    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            try {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            } catch (e: Exception) {
                null
            }
        }
        // Termux stores models in its home; that is the same data partition as AMC's.
        val free = try { StatFs(context.filesDir.absolutePath).availableBytes / (1024 * 1024) } catch (e: Exception) { 0L }
        return DeviceProfile(
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024),
            freeStorageMb = free,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuFeatures = LocalModelCatalog.parseCpuFeatures(cpuinfo),
            performanceCores = LocalModelCatalog.performanceCores(freqs),
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        )
    }
}

/**
 * One-tap setup of a local model through the Termux bridge (backend B), in resumable phases:
 * CHECK (phone, bridge, storage, model choice) → DOWNLOAD (Termux downloads in the background;
 * this side polls the progress) → VERIFY → START (llama-server) → BENCHMARK (decode speed and
 * tool-call probes with the real AMC tool schema; too slow → the next smaller model) → DONE.
 * The phase and the chosen model are saved in [store], so a run that was interrupted (app
 * closed, process killed, phone restarted, network lost) continues instead of starting over.
 */
class LocalModelSetup(
    private val context: Context,
    private val bridge: LlmBridge,
    private val store: LocalSetupStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(LOCAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = 2_000L,
    private val profiler: () -> DeviceProfile = { DeviceProfiler.profile(context) },
    private val catalogSource: (() -> List<CatalogModel>)? = null
) {
    constructor(context: Context, bridge: TermuxBridgeClient) : this(
        context, TermuxLlmBridge(bridge),
        LocalSetupStore(context.getSharedPreferences(LocalSetupStore.PREFS_NAME, Context.MODE_PRIVATE))
    )

    companion object {
        private const val TAG = "LocalModelSetup"
        private const val LOCAL_PROBE_TIMEOUT_S = 300L
        const val PORT = 8080
        const val MIN_DECODE_TOKENS_PER_S = 10.0
        const val TOOL_PROBES = 5
        /** Capability of bridges whose `llm_install` downloads in the background and reports progress. */
        const val BACKGROUND_INSTALL_CAPABILITY = "llm_background_install"
        /** How often a download that died in Termux is restarted (it resumes the partial file). */
        private const val MAX_DOWNLOAD_RESTARTS = 3
        /** Re-runs the AMC installer in Termux, which brings the bridge up to date. */
        val UPDATE_COMMAND: String get() = com.agent.mobile.core.termux.TermuxRunCommand.INSTALL_SCRIPT
    }

    sealed class Outcome {
        data class Ready(val model: CatalogModel, val ctx: Int, val tokensPerSecond: Double, val notes: List<String>) : Outcome()
        data class Failed(val reason: String, val error: SetupError) : Outcome()
    }

    data class Benchmark(val tokensPerSecond: Double, val validToolCalls: Int, val probes: Int) {
        val passed: Boolean get() = validToolCalls == probes && tokensPerSecond >= MIN_DECODE_TOKENS_PER_S
    }

    /** Replaced in tests; measures the running llama-server. */
    internal var benchmarkRunner: suspend () -> Benchmark = { benchmark() }

    fun catalog(): List<CatalogModel> = catalogSource?.invoke() ?: try {
        LocalModelCatalog.parse(context.assets.open("model_catalog.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        Log.w(TAG, "Model catalog unreadable: ${e.message}")
        emptyList()
    }

    /** Runs (or continues) the setup. [report] receives every progress change. */
    suspend fun run(report: (SetupProgress) -> Unit): Outcome {
        val outcome = try {
            runPhases(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Setup failed: ${e.message}", e)
            fail(SetupError.NETWORK, e.localizedMessage ?: "Unexpected error.")
        }
        if (outcome is Outcome.Ready) {
            store.update {
                it.copy(
                    phase = SetupPhase.DONE, modelId = outcome.model.id, ctx = outcome.ctx, error = null,
                    message = "Active: ${outcome.model.title} (%.1f tokens/s).".format(outcome.tokensPerSecond)
                )
            }
            report(SetupProgress(SetupPhase.DONE, "Local AI is active.", outcome.model.title))
        }
        return outcome
    }

    /** Stops a running download in Termux (the partial file is kept for "Resume"). */
    suspend fun cancel() {
        try {
            if (bridge.connected) bridge.action("llm_cancel", timeoutMs = 20_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed: ${e.message}")
        }
        store.update { it.copy(phase = SetupPhase.CANCELLED, error = null, message = "Cancelled. The download can be resumed.") }
    }

    private fun fail(error: SetupError, reason: String): Outcome.Failed {
        store.update { it.copy(phase = SetupPhase.FAILED, error = error, message = reason) }
        return Outcome.Failed(reason, error)
    }

    private suspend fun runPhases(report: (SetupProgress) -> Unit): Outcome {
        val catalog = catalog()
        val saved = store.load()
        val resumeModel = catalog.firstOrNull { it.id == saved.modelId }?.takeIf { saved.resumable }

        report(SetupProgress(SetupPhase.CHECK, "Connecting to Termux…"))
        store.update { it.copy(phase = if (resumeModel != null) it.phase else SetupPhase.CHECK, error = null) }
        if (!bridge.awaitConnected(5_000L)) {
            return fail(SetupError.BRIDGE, "The Termux bridge is not reachable. AMC tries to start it; check that Termux is installed and allowed to run in the background.")
        }
        if ("llm" !in bridge.capabilities || BACKGROUND_INSTALL_CAPABILITY !in bridge.capabilities) {
            return fail(SetupError.BRIDGE_OLD, "The AMC bridge in Termux is too old for this setup. Update it (AMC runs the installer in Termux), then try again.")
        }

        var choice: Recommendation.Model = if (resumeModel != null) {
            Recommendation.Model(resumeModel, saved.ctx.takeIf { it > 0 } ?: resumeModel.ctx, saved.threads.coerceAtLeast(2), emptyList())
        } else {
            report(SetupProgress(SetupPhase.CHECK, "Checking the phone…"))
            val profile = withContext(Dispatchers.IO) { profiler() }
            when (val r = LocalModelCatalog.recommend(profile, catalog)) {
                is Recommendation.Model -> r
                is Recommendation.NotRecommended -> {
                    // Would a model fit with more free storage? Then storage is the fix, not the phone.
                    val withSpace = LocalModelCatalog.recommend(profile.copy(freeStorageMb = Long.MAX_VALUE / 4), catalog)
                    return if (withSpace is Recommendation.Model) {
                        val need = withSpace.model.sizeMb + withSpace.model.sizeMb / 5
                        fail(
                            SetupError.STORAGE,
                            "Not enough free storage: %s needs about %.1f GB, %.1f GB are free."
                                .format(withSpace.model.title, need / 1024.0, profile.freeStorageMb / 1024.0)
                        )
                    } else fail(SetupError.NOT_SUPPORTED, r.reason)
                }
            }
        }

        while (true) {
            val model = choice.model
            store.update { it.copy(phase = SetupPhase.DOWNLOAD, modelId = model.id, ctx = choice.ctx, threads = choice.threads, error = null, message = "") }
            download(model, report)?.let { return it }

            store.update { it.copy(phase = SetupPhase.START) }
            report(SetupProgress(SetupPhase.START, "Starting the model…", model.title))
            val start = bridge.action(
                "llm_start",
                mapOf("model" to model.file, "ctx" to choice.ctx.toString(), "threads" to choice.threads.toString(), "port" to PORT.toString()),
                timeoutMs = 180_000L
            )
            if (!start.ok) {
                if (!bridge.connected) return fail(SetupError.BRIDGE, "The connection to Termux was lost while the model started.")
                return fail(SetupError.START, "llama-server did not start: ${lastLine(start.output)}")
            }

            store.update { it.copy(phase = SetupPhase.BENCHMARK) }
            report(SetupProgress(SetupPhase.BENCHMARK, "Testing speed and $TOOL_PROBES tool calls…", model.title))
            val bench = benchmarkRunner()
            if (bench.passed) return Outcome.Ready(model, choice.ctx, bench.tokensPerSecond, choice.notes)
            val smaller = LocalModelCatalog.smallerThan(model, catalog)
                ?: return fail(
                    SetupError.TOO_SLOW,
                    "The model is too slow or unreliable on this phone (%.1f tokens/s, %d/%d valid tool calls). Keep using a cloud model."
                        .format(bench.tokensPerSecond, bench.validToolCalls, bench.probes)
                )
            report(SetupProgress(SetupPhase.BENCHMARK, "Too slow (%.1f tokens/s); switching to ${smaller.title}…".format(bench.tokensPerSecond), model.title))
            choice = Recommendation.Model(smaller, maxOf(LocalModelCatalog.MIN_CTX, minOf(smaller.ctx, choice.ctx)), choice.threads, choice.notes)
        }
    }

    /**
     * Starts (or re-attaches to) the background download in Termux and follows it until the
     * file is verified. Returns null on success, otherwise the failure.
     */
    private suspend fun download(model: CatalogModel, report: (SetupProgress) -> Unit): Outcome.Failed? {
        val params = buildMap {
            put("model", model.file)
            put("url", model.url)
            put("size", (model.sizeMb * 1024 * 1024).toString())
            if (model.verified) put("sha256", model.sha256)
        }
        report(SetupProgress(SetupPhase.DOWNLOAD, "Preparing the download…", model.title))
        val started = bridge.action("llm_install", params, timeoutMs = 180_000L)
        if (!started.ok) {
            if (!bridge.connected) return fail(SetupError.BRIDGE, "The connection to Termux was lost.")
            return fail(SetupError.NETWORK, "The download could not start: ${lastLine(started.output)}")
        }
        val meter = ProgressMeter()
        var restarts = 0
        var status = started.status
        while (true) {
            val phase = status["install_phase"].orEmpty()
            val sameModel = status["install_model"] == model.file
            when {
                phase == "done" && sameModel -> {
                    report(SetupProgress(SetupPhase.VERIFY, "Download complete.", model.title))
                    return null
                }
                phase == "error" && sameModel -> return when (status["install_error"]) {
                    "checksum", "format" -> fail(SetupError.CHECKSUM, "The downloaded file was damaged or is not a model, so it was deleted. Download it again.")
                    "runtime" -> fail(SetupError.RUNTIME, "llama.cpp could not be installed in Termux (pkg install llama-cpp). Check Termux's internet access and try again.")
                    else -> fail(SetupError.NETWORK, "The download stopped (network or server). It resumes where it stopped.")
                }
                phase == "runtime" && sameModel ->
                    report(SetupProgress(SetupPhase.DOWNLOAD, "Installing llama.cpp in Termux…", model.title))
                phase == "verify" && sameModel -> {
                    store.update { it.copy(phase = SetupPhase.VERIFY) }
                    report(SetupProgress(SetupPhase.VERIFY, "Checking the file…", model.title))
                }
                phase == "download" && sameModel -> {
                    val bytes = status["install_bytes"]?.toLongOrNull() ?: 0L
                    val total = status["install_total"]?.toLongOrNull()?.takeIf { it > 0 } ?: model.sizeMb * 1024 * 1024
                    meter.update(bytes, clock())
                    report(
                        SetupProgress(
                            SetupPhase.DOWNLOAD, "Downloading ${model.title}…", model.title, bytes, total,
                            meter.bytesPerSecond.toLong(), meter.etaSeconds(bytes, total)
                        )
                    )
                }
                else -> {
                    // The download in Termux is not running (killed, phone restarted, another model): start it again.
                    if (restarts >= MAX_DOWNLOAD_RESTARTS) {
                        return fail(SetupError.NETWORK, "The download keeps stopping in Termux. It resumes where it stopped.")
                    }
                    restarts++
                    val again = bridge.action("llm_install", params, timeoutMs = 180_000L)
                    if (!again.ok && !bridge.connected) return fail(SetupError.BRIDGE, "The connection to Termux was lost.")
                    status = again.status
                    continue
                }
            }
            delay(pollMs)
            val next = bridge.action("llm_status", timeoutMs = 30_000L)
            if (!next.ok) {
                if (!bridge.connected) return fail(SetupError.BRIDGE, "The connection to Termux was lost. The download continues in Termux.")
                continue
            }
            status = next.status
        }
    }

    private fun lastLine(text: String) = text.lines().lastOrNull { it.isNotBlank() && !it.startsWith("status.") } ?: "unknown error"

    /** Decode speed from llama-server's timings and how many tool-call probes return valid calls. */
    suspend fun benchmark(): Benchmark = withContext(Dispatchers.IO) {
        val prompts = listOf(
            "Show me a notification that says hello.",
            "Read the file ~/notes.txt.",
            "Open the app Spotify.",
            "Remember that my favourite colour is green.",
            "Run the shell command 'uptime'."
        ).take(TOOL_PROBES)
        var valid = 0
        var speed = 0.0
        val tools = AgentTools.openAiTools(AgentTools.ALL.map { it.name }.filter { it !in AgentTools.LOCAL_PROFILE }.toSet())
        for (prompt in prompts) {
            val body = JSONObject()
                .put("model", "default")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("tools", tools)
                .put("max_tokens", 160)
                .put("temperature", 0)
            try {
                val request = Request.Builder().url("http://127.0.0.1:$PORT/v1/chat/completions")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optJSONObject("timings")?.optDouble("predicted_per_second")?.takeIf { !it.isNaN() }?.let { speed = maxOf(speed, it) }
                    val call = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
                    if (call != null && AgentTools.isKnown(call.optString("name"))) {
                        // The arguments must be a JSON object, as the agent parses them.
                        runCatching { JSONObject(call.optString("arguments")) }.onSuccess { valid++ }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Probe failed: ${e.message}")
            }
        }
        Benchmark(speed, valid, prompts.size)
    }
}
