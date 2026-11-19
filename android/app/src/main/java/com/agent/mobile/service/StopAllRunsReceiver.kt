package com.agent.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.agent.mobile.agent.RunRegistry

/** "Stop all agents" in the AMC notification: the emergency brake for every running agent run. */
class StopAllRunsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stopped = RunRegistry.stopAll()
        Toast.makeText(
            context,
            if (stopped > 0) "AMC: stopped $stopped agent run(s)" else "AMC: no agent run was active",
            Toast.LENGTH_SHORT
        ).show()
    }
}
