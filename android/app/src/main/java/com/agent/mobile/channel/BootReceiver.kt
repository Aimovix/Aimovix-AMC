package com.agent.mobile.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.mobile.AgentRuntime
import com.agent.mobile.ui.legal.LegalTexts
import kotlinx.coroutines.launch

/** Restarts the Telegram channel after a reboot or app update when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val prefs = AgentRuntime.get(context).preferenceManager
            if (prefs.loadTelegramEnabled() && prefs.loadAcceptedLegalVersion() >= LegalTexts.VERSION) {
                ChannelGatewayService.start(context)
            }
            // A local model setup that was under way continues (the download resumes).
            com.agent.mobile.service.scheduler.LocalModelSetupWorker.resumeIfUnfinished(context)
            // Chat runs that were working when the phone went down continue in the background (#7).
            if (prefs.loadAcceptedLegalVersion() >= LegalTexts.VERSION) {
                val runtime = AgentRuntime.get(context)
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        runtime.runCheckpointer.resumeInterrupted(emptySet())
                    } catch (e: Exception) {
                        Log.w("AmcBootReceiver", "Could not continue interrupted runs: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AmcBootReceiver", "Not starting channels: ${e.message}")
        }
    }
}
