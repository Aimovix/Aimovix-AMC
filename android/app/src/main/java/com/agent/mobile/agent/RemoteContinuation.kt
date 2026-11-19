package com.agent.mobile.agent

/**
 * Messaging-channel runs that run out of steps or time continue on their own (review finding
 * #19), like background tasks do: the same session gets a continuation note and another round,
 * at most [MAX_ROUNDS] extra rounds per message. A run that ended for any other reason (done,
 * stopped, limit, waiting for approval) is not continued. The user hears about each round.
 */
object RemoteContinuation {

    const val MAX_ROUNDS = 3

    const val NOTE =
        "[AMC] Continuation: the previous round of this chat task used up its step or time budget. Look at " +
            "what is already done above and continue the task from there. Do not redo finished steps."

    fun progressText(round: Int, max: Int = MAX_ROUNDS) =
        "⏳ Step budget used up. Continuing on my own (round $round of $max)…"

    /**
     * Runs [first]; while its result ran out of budget, runs [next] in the same session.
     * The result of the last round is returned with the refused and skipped actions of all rounds.
     */
    suspend fun run(
        first: suspend () -> AutonomousAgentEngine.UnattendedResult,
        next: suspend (sessionId: String) -> AutonomousAgentEngine.UnattendedResult,
        onContinue: suspend (round: Int) -> Unit = {},
        maxRounds: Int = MAX_ROUNDS
    ): AutonomousAgentEngine.UnattendedResult {
        var result = first()
        val denied = result.autoDenied.toMutableList()
        val skipped = result.skippedApprovals.toMutableList()
        var round = 0
        while (result.budgetExhausted && !result.stoppedByUser && round < maxRounds) {
            val sessionId = result.sessionId ?: break
            round++
            onContinue(round)
            result = next(sessionId)
            denied += result.autoDenied
            skipped += result.skippedApprovals
        }
        return if (round == 0) result else result.copy(autoDenied = denied, skippedApprovals = skipped)
    }
}
