package com.agent.mobile.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPairingTest {

    private val token = "a1".repeat(32)

    private fun answer(stdout: String = "", exitCode: Int = 0, stderr: String = "") =
        TermuxRunCommand.Result(exitCode = exitCode, stdout = stdout, stderr = stderr)

    @Test
    fun noAnswerPointsAtTheExternalAppsSetting() {
        assertTrue(TermuxPairing.classify(null) is TermuxPairing.Outcome.NeedsExternalApps)
    }

    @Test
    fun refusalAboutExternalAppsAsksForTheSetting() {
        val refused = TermuxRunCommand.Result(
            exitCode = -1, stdout = "", stderr = "", errCode = 2,
            errMessage = "allow-external-apps property is not set to true"
        )
        assertTrue(TermuxPairing.classify(refused) is TermuxPairing.Outcome.NeedsExternalApps)
    }

    @Test
    fun otherRefusalIsAPlainFailure() {
        val refused = TermuxRunCommand.Result(exitCode = -1, stdout = "", stderr = "", errCode = 1, errMessage = "Termux crashed")
        val outcome = TermuxPairing.classify(refused)
        assertTrue(outcome is TermuxPairing.Outcome.Failed)
        assertTrue((outcome as TermuxPairing.Outcome.Failed).message.contains("Termux crashed"))
    }

    @Test
    fun exitCode127MeansTheBridgeIsNotInstalled() {
        assertEquals(TermuxPairing.Outcome.NotInstalled, TermuxPairing.classify(answer(exitCode = 127, stderr = "not installed")))
    }

    @Test
    fun tokenInTheOutputIsReturned() {
        assertEquals(
            TermuxPairing.Outcome.Paired(token),
            TermuxPairing.classify(answer(stdout = "AMC bridge is already running.\n$token\n"))
        )
    }

    @Test
    fun missingTokenIsAFailureThatQuotesTermux() {
        val outcome = TermuxPairing.classify(answer(exitCode = 0, stderr = "No token found."))
        assertTrue(outcome is TermuxPairing.Outcome.Failed)
        assertTrue((outcome as TermuxPairing.Outcome.Failed).message.contains("No token found."))
    }
}
