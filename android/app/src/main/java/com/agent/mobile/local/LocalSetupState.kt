package com.agent.mobile.local

import android.content.SharedPreferences
import com.agent.mobile.data.network.LlmActionResult
import com.agent.mobile.data.network.TermuxBridgeClient
import org.json.JSONObject

/** Phases of the local AI setup, in order. DONE, FAILED and CANCELLED end a run. */
enum class SetupPhase { IDLE, CHECK, DOWNLOAD, VERIFY, START, BENCHMARK, DONE, FAILED, CANCELLED }

/** Why a setup stopped, each with exactly one fix the card offers. */
enum class SetupError(val fixLabel: String?) {
    /** The Termux bridge could not be reached or started. */
    BRIDGE("Try again"),
    /** The bridge in Termux is too old for background downloads. */
    BRIDGE_OLD("Copy update command"),
    /** Download interrupted (no network, server error); the partial file is kept. */
    NETWORK("Resume"),
    /** Not enough free storage for the model. */
    STORAGE("Free up storage"),
    /** The file did not match its checksum or is no model; it was deleted. */
    CHECKSUM("Download again"),
    /** llama.cpp could not be installed in Termux. */
    RUNTIME("Try again"),
    /** llama-server did not start. */
    START("Try again"),
    /** Even the smallest model is too slow or unreliable on this phone. */
    TOO_SLOW(null),
    /** The phone cannot run a local model (32-bit, too little RAM). */
    NOT_SUPPORTED(null);

    /** Errors that may go away by themselves: the worker retries them later. */
    val retryable: Boolean get() = this == BRIDGE || this == NETWORK
}

/** What the setup reports while it runs (shown as the progress bar). */
data class SetupProgress(
    val phase: SetupPhase,
    val message: String,
    val modelTitle: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long = -1
) {
    /** 0..1 while the size is known, null for phases without a measurable amount (indeterminate bar). */
    val fraction: Float? get() = if (total > 0 && phase == SetupPhase.DOWNLOAD) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Persisted state: lets the setup continue after the app was closed, killed or the phone restarted. */
data class LocalSetupState(
    val phase: SetupPhase = SetupPhase.IDLE,
    val modelId: String = "",
    val ctx: Int = 0,
    val threads: Int = 0,
    val error: SetupError? = null,
    val message: String = "",
    val updatedAt: Long = 0
) {
    /** A run stopped half-way with a model chosen: the next run continues the download. */
    val resumable: Boolean
        get() = modelId.isNotEmpty() && when (phase) {
            SetupPhase.DOWNLOAD, SetupPhase.VERIFY, SetupPhase.START, SetupPhase.BENCHMARK, SetupPhase.CANCELLED -> true
            SetupPhase.FAILED -> error == SetupError.NETWORK || error == SetupError.BRIDGE
            else -> false
        }

    /** True while a setup is under way (a worker should be running). */
    val active: Boolean
        get() = phase in setOf(SetupPhase.CHECK, SetupPhase.DOWNLOAD, SetupPhase.VERIFY, SetupPhase.START, SetupPhase.BENCHMARK)

    fun toJson(): String = JSONObject()
        .put("phase", phase.name).put("modelId", modelId).put("ctx", ctx).put("threads", threads)
        .put("error", error?.name ?: "").put("message", message).put("updatedAt", updatedAt)
        .toString()

    companion object {
        fun fromJson(text: String?): LocalSetupState = try {
            val o = JSONObject(text ?: "")
            LocalSetupState(
                phase = runCatching { SetupPhase.valueOf(o.optString("phase")) }.getOrDefault(SetupPhase.IDLE),
                modelId = o.optString("modelId"),
                ctx = o.optInt("ctx"),
                threads = o.optInt("threads"),
                error = o.optString("error").takeIf { it.isNotEmpty() }?.let { runCatching { SetupError.valueOf(it) }.getOrNull() },
                message = o.optString("message"),
                updatedAt = o.optLong("updatedAt")
            )
        } catch (e: Exception) {
            LocalSetupState()
        }
    }
}

/** Stores [LocalSetupState] in plain app preferences (no secrets in it). */
class LocalSetupStore(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    fun load(): LocalSetupState = LocalSetupState.fromJson(prefs.getString(KEY, null))

    fun save(state: LocalSetupState) {
        prefs.edit().putString(KEY, state.copy(updatedAt = clock()).toJson()).apply()
    }

    fun update(transform: (LocalSetupState) -> LocalSetupState) = save(transform(load()))

    companion object {
        const val PREFS_NAME = "amc_local_setup"
        private const val KEY = "state"
    }
}

/**
 * Download speed and time left from successive byte counts. The speed is smoothed so the
 * estimate does not jump with every poll.
 */
class ProgressMeter(private val smoothing: Double = 0.3) {
    private var lastBytes = -1L
    private var lastTimeMs = 0L
    var bytesPerSecond = 0.0
        private set

    fun update(bytes: Long, nowMs: Long) {
        if (lastBytes >= 0 && nowMs > lastTimeMs && bytes >= lastBytes) {
            val speed = (bytes - lastBytes) * 1000.0 / (nowMs - lastTimeMs)
            bytesPerSecond = if (bytesPerSecond == 0.0) speed else smoothing * speed + (1 - smoothing) * bytesPerSecond
        }
        lastBytes = bytes
        lastTimeMs = nowMs
    }

    /** Seconds left, or -1 while the speed is unknown. */
    fun etaSeconds(bytes: Long, total: Long): Long =
        if (bytesPerSecond <= 0.0 || total <= 0 || bytes >= total) -1L else ((total - bytes) / bytesPerSecond).toLong()
}

/** The part of the Termux bridge the setup needs (a fake in tests). */
interface LlmBridge {
    val capabilities: Set<String>
    val connected: Boolean
    suspend fun awaitConnected(timeoutMs: Long): Boolean
    suspend fun action(action: String, params: Map<String, String> = emptyMap(), timeoutMs: Long = 120_000L): LlmActionResult
}

class TermuxLlmBridge(private val client: TermuxBridgeClient) : LlmBridge {
    override val capabilities: Set<String> get() = client.serverCapabilities
    override val connected: Boolean
        get() = client.connectionStatus.value is com.agent.mobile.data.model.ConnectionStatus.Connected
    override suspend fun awaitConnected(timeoutMs: Long): Boolean = client.awaitConnected(timeoutMs)
    override suspend fun action(action: String, params: Map<String, String>, timeoutMs: Long): LlmActionResult =
        client.llmAction(action, params, timeoutMs)
}
