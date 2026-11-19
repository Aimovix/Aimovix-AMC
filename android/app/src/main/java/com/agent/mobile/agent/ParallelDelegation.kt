package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall

/**
 * Sub-agents of one turn run side by side (review finding #25): when the model hands out several
 * sub-tasks in one turn, up to [MAX_PARALLEL] of them start together instead of one after the
 * other. The bridge daemon runs up to four commands at once, so three children leave room for
 * the parent. Their approvals still reach the user one at a time.
 */
object ParallelDelegation {

    const val MAX_PARALLEL = 3

    /**
     * How many calls starting at [index] run together: the consecutive delegate_task calls from
     * there (at most [max]); 1 when the call at [index] runs alone.
     */
    fun groupSize(calls: List<ToolCall>, index: Int, max: Int = MAX_PARALLEL): Int {
        var n = 0
        while (index + n < calls.size && n < max && calls[index + n].name == AgentTools.DELEGATE_TASK) n++
        return if (n >= 2) n else 1
    }
}
