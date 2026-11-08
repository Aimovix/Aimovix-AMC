package com.agent.mobile.core.termux

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to Termux through its official `RUN_COMMAND` intent (RunCommandService), so AMC can
 * start the bridge and fetch the pairing token itself instead of asking the user to type
 * commands in Termux.
 *
 * Requirements (both handled by the setup screen / installer):
 * - AMC holds the runtime permission `com.termux.permission.RUN_COMMAND`.
 * - `allow-external-apps = true` in `~/.termux/termux.properties` (set by setup.sh).
 *
 * The WebSocket bridge stays the channel for commands (live output, interrupts, files);
 * RUN_COMMAND only (re)starts it and helps with pairing.
 */
object TermuxRunCommand {
    private const val TAG = "TermuxRunCommand"

    const val TERMUX_PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    internal const val EXTRA_REQUEST_ID = "amc_request_id"

    // Keys of the result bundle Termux puts into the pending intent.
    internal const val RESULT_BUNDLE = "result"
    internal const val RESULT_STDOUT = "stdout"
    internal const val RESULT_STDERR = "stderr"
    internal const val RESULT_EXIT_CODE = "exitCode"
    internal const val RESULT_ERR = "err"
    internal const val RESULT_ERRMSG = "errmsg"

    // Paths inside Termux's own app data, not AMC's.
    @SuppressLint("SdCardPath")
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    @SuppressLint("SdCardPath")
    const val HOME = "/data/data/com.termux/files/home"

    /** Starts the bridge if it is not running (idempotent). */
    const val START_BRIDGE_SCRIPT =
        "command -v amc >/dev/null 2>&1 || { echo 'AMC bridge is not installed' >&2; exit 127; }; amc start-silent; amc status"

    /** Starts the bridge if needed and prints the pairing token. */
    const val FETCH_TOKEN_SCRIPT =
        "command -v amc >/dev/null 2>&1 || { echo 'AMC bridge is not installed' >&2; exit 127; }; " +
            "amc start-silent >/dev/null 2>&1; amc token"

    /** One-time line the user runs in Termux to allow RUN_COMMAND (done by setup.sh for new installs). */
    const val ALLOW_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux && grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties; termux-reload-settings"

    /** Installer, run in a visible Termux session so the user can follow it. */
    const val INSTALL_SCRIPT =
        "curl --fail --show-error --location https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh " +
            "-o ~/setup-amc.sh && bash ~/setup-amc.sh"

    enum class Availability { TERMUX_MISSING, PERMISSION_MISSING, READY }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        /** Termux's own error code; -1 (RESULT_OK) means the command could be started. */
        val errCode: Int = -1,
        val errMessage: String = ""
    ) {
        val ok: Boolean get() = errCode == -1 && exitCode == 0
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result>>()
    private val requestCodes = AtomicInteger(4_000)

    fun availability(context: Context): Availability {
        val installed = try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return Availability.TERMUX_MISSING
        val granted = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
        return if (granted) Availability.READY else Availability.PERMISSION_MISSING
    }

    internal fun buildIntent(script: String, background: Boolean, label: String, resultIntent: PendingIntent?): Intent =
        Intent(ACTION).apply {
            setClassName(TERMUX_PACKAGE, SERVICE)
            putExtra(EXTRA_PATH, BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", script))
            putExtra(EXTRA_WORKDIR, HOME)
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_LABEL, label)
            if (resultIntent != null) putExtra(EXTRA_PENDING_INTENT, resultIntent)
        }

    private fun send(context: Context, intent: Intent): Boolean = try {
        // RunCommandService moves itself to the foreground, so this also works from the background.
        context.startForegroundService(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not reach Termux: ${e.message}")
        false
    }

    /** Fire and forget, e.g. starting the bridge. Returns false when Termux could not be reached. */
    fun start(context: Context, script: String, label: String = "AMC", background: Boolean = true): Boolean {
        if (availability(context) != Availability.READY) return false
        return send(context.applicationContext, buildIntent(script, background, label, null))
    }

    /**
     * Runs [script] in the background and waits for Termux's result. Returns null when Termux did
     * not answer in time (most often because `allow-external-apps` is not enabled).
     */
    suspend fun run(context: Context, script: String, label: String = "AMC", timeoutMs: Long = 20_000L): Result? {
        if (availability(context) != Availability.READY) return null
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result>()
        pending[id] = deferred
        try {
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val resultIntent = PendingIntent.getBroadcast(
                app,
                requestCodes.incrementAndGet(),
                Intent(app, TermuxResultReceiver::class.java).putExtra(EXTRA_REQUEST_ID, id),
                flags
            )
            if (!send(app, buildIntent(script, true, label, resultIntent))) return null
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    internal fun onResult(requestId: String?, bundle: Bundle?) {
        val deferred = requestId?.let { pending[it] } ?: return
        deferred.complete(parseResult(bundle))
    }

    internal fun parseResult(bundle: Bundle?): Result {
        if (bundle == null) return Result(-1, "", "", errCode = 1, errMessage = "Termux returned no result.")
        return Result(
            exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
            stdout = bundle.getString(RESULT_STDOUT).orEmpty(),
            stderr = bundle.getString(RESULT_STDERR).orEmpty(),
            errCode = bundle.getInt(RESULT_ERR, -1),
            errMessage = bundle.getString(RESULT_ERRMSG).orEmpty()
        )
    }

    private val TOKEN_REGEX = Regex("^[0-9a-fA-F]{32,128}$")

    /** Extracts the bridge token (hex) from the output of `amc token`. */
    fun extractToken(stdout: String): String? =
        stdout.lines().map { it.trim() }.lastOrNull { TOKEN_REGEX.matches(it) }
}

/** Receives command results from Termux (sent through the PendingIntent of [TermuxRunCommand.run]). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxRunCommand.onResult(
            intent.getStringExtra(TermuxRunCommand.EXTRA_REQUEST_ID),
            intent.getBundleExtra(TermuxRunCommand.RESULT_BUNDLE)
        )
    }
}
