package com.agent.mobile.entry

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.agent.mobile.agent.RunRegistry

/**
 * Quick Settings tile "Stop all agents" (Teil E, E3): the emergency brake from anywhere, also
 * from the lock screen's shade. Active while at least one agent run works.
 */
class StopAgentsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val stopped = RunRegistry.stopAll()
        Toast.makeText(
            applicationContext,
            if (stopped > 0) "AMC: stopped $stopped agent run(s)" else "AMC: no agent run was active",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = RunRegistry.active.value.size
        tile.state = if (running > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running > 0) "$running running" else "None running"
        }
        tile.updateTile()
    }
}
