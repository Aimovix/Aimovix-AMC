package com.agent.mobile.core.termux

import android.content.Context

/**
 * Pairs AMC with the Termux bridge through RUN_COMMAND: starts the bridge if needed and reads the
 * token. [classify] maps Termux's answer to one outcome, so every case can be explained on screen.
 */
object TermuxPairing {

    sealed class Outcome {
        data class Paired(val token: String) : Outcome()

        /** The bridge is not installed in Termux yet (the start script exits with 127). */
        object NotInstalled : Outcome()

        /** Termux did not run the command: "Allow external apps" is off in Termux. */
        data class NeedsExternalApps(val message: String) : Outcome()

        data class Failed(val message: String) : Outcome()
    }

    /** Pure mapping from Termux's answer to an outcome. */
    fun classify(result: TermuxRunCommand.Result?): Outcome {
        if (result == null) {
            return Outcome.NeedsExternalApps("Termux did not answer. Allow external apps in Termux once, then try again.")
        }
        if (result.errCode != -1) {
            val reason = result.errMessage.ifBlank { "error ${result.errCode}" }.take(300)
            return if (reason.contains("allow-external-apps", ignoreCase = true)) {
                Outcome.NeedsExternalApps("Termux refused: $reason")
            } else {
                Outcome.Failed("Termux refused: $reason")
            }
        }
        if (result.exitCode == 127) return Outcome.NotInstalled
        TermuxRunCommand.extractToken(result.stdout)?.let { return Outcome.Paired(it) }
        val detail = result.stderr.ifBlank { result.stdout }.trim().take(300)
        return Outcome.Failed(if (detail.isEmpty()) "No token received." else "No token received. Termux said: $detail")
    }

    /** Runs the pairing script in Termux. Checks the preconditions first, so the message is specific. */
    suspend fun pair(context: Context): Outcome = when (TermuxRunCommand.availability(context)) {
        TermuxRunCommand.Availability.TERMUX_MISSING -> Outcome.Failed("Termux is not installed yet.")
        TermuxRunCommand.Availability.PERMISSION_MISSING -> Outcome.Failed("Allow AMC to run commands in Termux first.")
        TermuxRunCommand.Availability.READY -> classify(
            TermuxRunCommand.run(context, TermuxRunCommand.FETCH_TOKEN_SCRIPT, "AMC: pair app", timeoutMs = 25_000L)
        )
    }
}
