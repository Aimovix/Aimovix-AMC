package com.agent.mobile.phone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Optional ADB-level commands through Shizuku (no root). Everything else in AMC works without it.
 * Commands run as an argument list in Shizuku's shell process, never through `sh -c`.
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    const val PERMISSION_REQUEST_CODE = 4711
    private const val MAX_OUTPUT = 8_000

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun status(context: Context): Status = try {
        when {
            !isInstalled(context) -> Status.NOT_INSTALLED
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Shizuku status failed: ${e.message}")
        if (isInstalled(context)) Status.NOT_RUNNING else Status.NOT_INSTALLED
    }

    /** Shows Shizuku's permission dialog. Returns false when Shizuku is not running. */
    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) false else {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        false
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

    /** Runs [args] with ADB rights. Throws IllegalStateException when Shizuku is not ready. */
    suspend fun run(context: Context, args: List<String>, timeoutMs: Long = 30_000L): Result = withContext(Dispatchers.IO) {
        check(status(context) == Status.READY) { "Shizuku is not running or AMC has no Shizuku permission." }
        // Shizuku 13 keeps newProcess private in favour of user services; the method is stable.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = method.invoke(null, args.toTypedArray(), null, null) as Process
        try {
            coroutineScope {
                val out = async { process.inputStream.bufferedReader().use { it.readText() } }
                val err = async { process.errorStream.bufferedReader().use { it.readText() } }
                val finished = withTimeoutOrNull(timeoutMs) {
                    while (!process.waitFor(100, TimeUnit.MILLISECONDS)) yield()
                    true
                } ?: false
                if (!finished) {
                    process.destroy()
                    return@coroutineScope Result(-1, "", "Timed out after ${timeoutMs / 1000} s.", timedOut = true)
                }
                Result(process.exitValue(), out.await().take(MAX_OUTPUT), err.await().take(MAX_OUTPUT))
            }
        } finally {
            process.destroy()
        }
    }
}
