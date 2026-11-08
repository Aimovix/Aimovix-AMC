package com.agent.mobile.core.termux

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TermuxRunCommandTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun installTermux() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = TermuxRunCommand.TERMUX_PACKAGE })
    }

    private fun grantPermission() = shadowOf(app).grantPermissions(TermuxRunCommand.PERMISSION)

    @Test
    fun availabilityReflectsTermuxAndPermission() {
        assertEquals(TermuxRunCommand.Availability.TERMUX_MISSING, TermuxRunCommand.availability(app))
        installTermux()
        assertEquals(TermuxRunCommand.Availability.PERMISSION_MISSING, TermuxRunCommand.availability(app))
        grantPermission()
        assertEquals(TermuxRunCommand.Availability.READY, TermuxRunCommand.availability(app))
    }

    @Test
    fun intentTargetsRunCommandServiceWithBashScript() {
        val intent = TermuxRunCommand.buildIntent("amc start", background = true, label = "x", resultIntent = null)
        assertEquals("com.termux.RUN_COMMAND", intent.action)
        assertEquals("com.termux.app.RunCommandService", intent.component?.className)
        assertEquals(TermuxRunCommand.BASH, intent.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertArrayEquals(arrayOf("-lc", "amc start"), intent.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS"))
        assertTrue(intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
    }

    @Test
    fun startDoesNothingWithoutPermission() {
        installTermux()
        assertFalse(TermuxRunCommand.start(app, "amc start"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun runReturnsTheResultTermuxSendsBack() = runBlocking {
        installTermux()
        grantPermission()
        val token = "a".repeat(64)
        val call = async { TermuxRunCommand.run(app, TermuxRunCommand.FETCH_TOKEN_SCRIPT, timeoutMs = 5_000) }

        var sent: Intent? = null
        withTimeout(3_000) {
            while (sent == null) {
                sent = shadowOf(app).nextStartedService
                if (sent == null) delay(10)
            }
        }
        @Suppress("DEPRECATION")
        val pending = sent!!.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")
        assertNotNull(pending)
        val requestId = shadowOf(pending).savedIntent.getStringExtra(TermuxRunCommand.EXTRA_REQUEST_ID)
        TermuxRunCommand.onResult(requestId, Bundle().apply {
            putString("stdout", "AMC bridge is already running.\n$token\n")
            putString("stderr", "")
            putInt("exitCode", 0)
            putInt("err", -1)
        })
        val result = call.await()
        assertNotNull(result)
        assertTrue(result!!.ok)
        assertEquals(token, TermuxRunCommand.extractToken(result.stdout))
    }

    @Test
    fun runTimesOutWhenTermuxDoesNotAnswer() = runBlocking {
        installTermux()
        grantPermission()
        assertNull(TermuxRunCommand.run(app, "true", timeoutMs = 200))
    }

    @Test
    fun parseResultReportsTermuxErrors() {
        val r = TermuxRunCommand.parseResult(Bundle().apply {
            putInt("err", 2)
            putString("errmsg", "allow-external-apps property is not set to true")
        })
        assertFalse(r.ok)
        assertTrue(r.errMessage.contains("allow-external-apps"))
        assertFalse(TermuxRunCommand.parseResult(null).ok)
    }

    @Test
    fun extractTokenIgnoresOtherOutput() {
        assertNull(TermuxRunCommand.extractToken("No token found. Start the bridge first."))
        assertEquals("ab".repeat(32), TermuxRunCommand.extractToken("hello\n${"ab".repeat(32)}\n"))
    }

    @Test
    fun bridgeStarterIsRateLimited() {
        installTermux()
        grantPermission()
        var now = 1_000_000L
        val starter = BridgeStarter(app) { now }
        val bridge = TermuxBridgeClient(token = "t")
        assertTrue(starter.kick(bridge))
        assertFalse("Second kick within the interval is skipped", starter.kick(bridge))
        now += BridgeStarter.MIN_INTERVAL_MS + 1
        assertTrue(starter.kick(bridge))
        val started = shadowOf(app).nextStartedService
        assertTrue(started.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!![1].contains("amc start-silent"))
    }

    @Test
    fun allowExternalAppsCommandIsIdempotentShell() {
        assertTrue(TermuxRunCommand.ALLOW_EXTERNAL_APPS_COMMAND.contains("grep -q"))
        assertTrue(Build.VERSION.SDK_INT > 0)
    }
}
