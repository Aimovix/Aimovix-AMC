package com.agent.mobile.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.Dispatchers
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
 * One-click setup of a local model through the Termux bridge (backend B): detect the phone,
 * choose a model, download and verify it, start llama-server, benchmark it (decode speed and
 * tool-call probes with the real AMC tool schema), and step down to a smaller model when it is
 * too slow. [onStep] reports progress for the UI.
 */
class LocalModelSetup(
    private val context: Context,
    private val bridge: TermuxBridgeClient,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(LOCAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LocalModelSetup"
        private const val LOCAL_PROBE_TIMEOUT_S = 300L
        const val PORT = 8080
        const val MIN_DECODE_TOKENS_PER_S = 10.0
        const val TOOL_PROBES = 5
    }

    sealed class Outcome {
        data class Ready(val model: CatalogModel, val ctx: Int, val tokensPerSecond: Double, val notes: List<String>) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    data class Benchmark(val tokensPerSecond: Double, val validToolCalls: Int, val probes: Int) {
        val passed: Boolean get() = validToolCalls == probes && tokensPerSecond >= MIN_DECODE_TOKENS_PER_S
    }

    fun catalog(): List<CatalogModel> = try {
        LocalModelCatalog.parse(context.assets.open("model_catalog.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        Log.w(TAG, "Model catalog unreadable: ${e.message}")
        emptyList()
    }

    suspend fun run(onStep: (String) -> Unit): Outcome {
        onStep("Detecting the phone…")
        val profile = withContext(Dispatchers.IO) { DeviceProfiler.profile(context) }
        if (!bridge.awaitConnected(5_000L)) return Outcome.Failed("The Termux bridge is not connected. Start it with 'amc start' in Termux.")
        if ("llm" !in bridge.serverCapabilities) return Outcome.Failed("The Termux bridge is too old for local models. Update it with 'amc update'.")
        val catalog = catalog()
        var choice = when (val r = LocalModelCatalog.recommend(profile, catalog)) {
            is Recommendation.NotRecommended -> return Outcome.Failed(r.reason)
            is Recommendation.Model -> r
        }
        while (true) {
            val model = choice.model
            onStep("Downloading ${model.title} (this can take a while)…")
            val install = bridge.llmAction(
                "llm_install",
                mapOf("model" to model.file, "url" to model.url) + (if (model.verified) mapOf("sha256" to model.sha256) else emptyMap()),
                timeoutMs = 65 * 60_000L
            )
            if (!install.ok) return Outcome.Failed("Download failed: ${install.output.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}")
            onStep("Starting the model…")
            val start = bridge.llmAction(
                "llm_start",
                mapOf("model" to model.file, "ctx" to choice.ctx.toString(), "threads" to choice.threads.toString(), "port" to PORT.toString()),
                timeoutMs = 150_000L
            )
            if (!start.ok) return Outcome.Failed("llama-server did not start: ${start.output.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}")
            onStep("Benchmark: speed and $TOOL_PROBES tool-call probes…")
            val bench = benchmark()
            if (bench.passed) return Outcome.Ready(model, choice.ctx, bench.tokensPerSecond, choice.notes)
            val smaller = LocalModelCatalog.smallerThan(model, catalog)
                ?: return Outcome.Failed(
                    "The model is too slow or unreliable on this phone (%.1f tokens/s, %d/%d valid tool calls). Keep using a cloud model."
                        .format(bench.tokensPerSecond, bench.validToolCalls, bench.probes)
                )
            onStep("Too slow (%.1f tokens/s); trying the smaller ${smaller.title}…".format(bench.tokensPerSecond))
            choice = Recommendation.Model(smaller, maxOf(LocalModelCatalog.MIN_CTX, minOf(smaller.ctx, choice.ctx)), choice.threads, choice.notes)
        }
    }

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
