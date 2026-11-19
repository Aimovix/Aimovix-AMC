package com.agent.mobile.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.agent.mobile.MainActivity
import com.agent.mobile.agent.RunRegistry

/**
 * Receives shares and launcher shortcuts and hands them to the one running MainActivity (via
 * onNewIntent), so a share never starts a second chat engine in the sharing app's task. It runs
 * in its own task, shows nothing and finishes at once. Each subclass accepts only its [actions]:
 * the exported share entry cannot be used by other apps to stop runs.
 */
abstract class EntryRelayActivity : Activity() {

    protected abstract val actions: Set<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action in actions) relay(intent)
        finish()
    }

    private fun relay(intent: Intent?) {
        when (intent?.action) {
            EntryIntents.ACTION_STOP_ALL -> {
                val stopped = RunRegistry.stopAll()
                Toast.makeText(
                    this,
                    if (stopped > 0) "AMC: stopped $stopped agent run(s)" else "AMC: no agent run was active",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            EntryIntents.ACTION_NEW_TASK -> open { putExtra(EntryIntents.EXTRA_NEW_TASK, true) }
            Intent.ACTION_SEND -> {
                val draft = EntryIntents.draftFromShare(intent) ?: return
                open {
                    putExtra(EntryIntents.EXTRA_DRAFT_TEXT, draft.text)
                    draft.imageUri?.let {
                        putExtra(EntryIntents.EXTRA_DRAFT_IMAGE, it.toString())
                        // Pass on the read grant of the sharing app for this one image.
                        setDataAndType(it, intent.type)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    private fun open(extras: Intent.() -> Unit) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply(extras)
        )
    }
}

/** "Share to AMC" from other apps (exported, ACTION_SEND only). */
class ShareRelayActivity : EntryRelayActivity() {
    override val actions = setOf(Intent.ACTION_SEND)
}

/** Launcher shortcuts "New task" and "Stop all agents" (not exported). */
class ShortcutRelayActivity : EntryRelayActivity() {
    override val actions = setOf(EntryIntents.ACTION_NEW_TASK, EntryIntents.ACTION_STOP_ALL)
}
