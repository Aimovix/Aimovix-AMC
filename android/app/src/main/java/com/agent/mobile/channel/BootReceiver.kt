package com.agent.mobile.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.mobile.AgentRuntime
import com.agent.mobile.ui.legal.LegalTexts

/** Restarts the Telegram channel after a reboot or app update when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val prefs = AgentRuntime.get(context).preferenceManager
            if (prefs.loadTelegramEnabled() && prefs.loadAcceptedLegalVersion() >= LegalTexts.VERSION) {
                ChannelGatewayService.start(context)
            }
        } catch (e: Exception) {
            Log.w("AmcBootReceiver", "Not starting channels: ${e.message}")
        }
    }
}
