package com.agent.mobile.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Routes Approve / Reject from places outside the chat (the approval notification's action
 * buttons) to the run that is waiting. Each pending approval registers its tool call id; the
 * first decision wins, later ones are ignored.
 */
object ApprovalActions {
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun register(callId: String, decide: (Boolean) -> Unit) {
        pending[callId] = decide
    }

    fun unregister(callId: String) {
        pending.remove(callId)
    }

    /** Returns true when a run was waiting for [callId]. */
    fun decide(callId: String, approved: Boolean): Boolean {
        val decide = pending.remove(callId) ?: return false
        decide(approved)
        return true
    }
}
