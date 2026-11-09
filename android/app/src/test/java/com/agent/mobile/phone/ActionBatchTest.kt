package com.agent.mobile.phone

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.ApprovalActions
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.security.RiskLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** screen_actions parsing and risk aggregation, screen deltas, and small helpers of v1.7.1. */
@RunWith(RobolectricTestRunner::class)
class ActionBatchTest {

    private fun steps(vararg s: JSONObject) = JSONArray().apply { s.forEach { put(it) } }.toString()
    private fun step(action: String, vararg kv: Pair<String, Any>) = JSONObject().put("action", action).apply { kv.forEach { put(it.first, it.second) } }
    private fun batch(steps: String) = ToolCall(id = "b1", name = PhoneTools.SCREEN_BATCH, arguments = mapOf("steps" to steps))

    private val notes = PhoneRiskContext(foregroundPackage = "com.example.notes", foregroundLabel = "Notes")

    @Test
    fun parsesStepsIntoSingleToolCalls() {
        val parsed = ActionBatchParser.parse(
            steps(
                step("tap", "target" to "e3"),
                step("type", "target" to "e5", "text" to "Milk", "submit" to true),
                step("scroll", "direction" to "down"),
                step("global", "global" to "back"),
                step("wait_for", "text" to "Saved", "expect" to "Saved")
            )
        )
        assertNull(parsed.error)
        assertEquals(5, parsed.steps.size)
        val type = parsed.steps[1].toToolCall("b1", 1)
        assertEquals(PhoneTools.SCREEN_TYPE, type.name)
        assertEquals("b1#2", type.id)
        assertEquals("Milk", type.arguments["text"])
        assertEquals("true", type.arguments["submit"])
        assertEquals("back", parsed.steps[3].args["action"])
        assertEquals("5", parsed.steps[4].args["timeout_seconds"])
        assertEquals("Saved", parsed.steps[4].expect)
    }

    @Test
    fun rejectsInvalidBatchesBeforeAnythingRuns() {
        assertNotNull(ActionBatchParser.parse(null).error)
        assertNotNull(ActionBatchParser.parse("not json").error)
        assertNotNull(ActionBatchParser.parse("[]").error)
        assertNotNull(ActionBatchParser.parse(steps(step("launch_missiles"))).error)
        assertNotNull(ActionBatchParser.parse(steps(step("tap"))).error)
        assertNotNull(ActionBatchParser.parse(steps(step("scroll", "direction" to "sideways"))).error)
        assertNotNull(ActionBatchParser.parse(steps(step("global", "global" to "notifications"))).error)
        val nine = steps(*Array(9) { step("scroll", "direction" to "down") })
        assertTrue(ActionBatchParser.parse(nine).error!!.contains("At most"))
    }

    @Test
    fun redactsTypedTextOnly() {
        val raw = steps(step("type", "text" to "secret", "target" to "e1"), step("tap", "target" to "e2"))
        val redacted = ActionBatchParser.redactedSteps(raw)
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("e2"))
        val call = batch(raw)
        assertFalse(PhoneTools.redacted(call).arguments["steps"]!!.contains("secret"))
        assertFalse(AgentTools.describe(call, redact = true).contains("secret"))
        assertTrue(AgentTools.describe(call).startsWith("screen_actions: "))
    }

    @Test
    fun safeBatchIsNotAlwaysConfirmed() {
        val a = PhoneToolPolicy.assess(batch(steps(step("tap", "target" to "e3"), step("scroll", "direction" to "down"))), notes)
        assertFalse(a.isBlocked)
        assertFalse(a.alwaysConfirm)
        assertEquals(RiskLevel.MEDIUM, a.level)
    }

    @Test
    fun oneBlockedStepBlocksTheBatch() {
        val a = PhoneToolPolicy.assess(
            batch(steps(step("tap", "target" to "e3"), step("type", "target" to "e4", "text" to "x"))),
            notes.copy(foregroundPackage = PhoneRiskContext.OWN_PACKAGE)
        )
        assertTrue(a.isBlocked)
        assertTrue(a.reason, a.reason.startsWith("Step 1"))
        assertTrue(PhoneToolPolicy.assess(batch("[]"), notes).isBlocked)
    }

    @Test
    fun sendingStepMakesTheWholeBatchAskWithoutGrantKey() {
        val ctx = notes.copy(
            stepTargets = listOf(TargetInfo("To"), TargetInfo("Send", viewId = "com.example:id/send"))
        )
        val a = PhoneToolPolicy.assess(batch(steps(step("type", "target" to "e1", "text" to "hi"), step("tap", "target" to "e9"))), ctx)
        assertTrue(a.alwaysConfirm)
        assertNull(a.grantKey)
        assertTrue(a.reason, a.reason.contains("⚠"))
    }

    @Test
    fun readingOnePrivateAppKeepsItsGrantKey() {
        val whatsapp = PhoneRiskContext(foregroundPackage = "com.whatsapp", foregroundLabel = "WhatsApp",
            stepTargets = listOf(TargetInfo("Anna"), null))
        val a = PhoneToolPolicy.assess(batch(steps(step("tap", "target" to "e3"), step("scroll", "direction" to "down"))), whatsapp)
        assertTrue(a.alwaysConfirm)
        assertEquals("read:com.whatsapp", a.grantKey)
    }

    @Test
    fun remoteRunsAskForEveryBatch() {
        assertTrue(PhoneToolPolicy.requiresApprovalInMode(PhoneTools.SCREEN_BATCH, com.agent.mobile.agent.RunMode.REMOTE))
    }

    @Test
    fun batchSchemaHasArrayItems() {
        val tools = AgentTools.openAiTools()
        val batch = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == PhoneTools.SCREEN_BATCH }
        val steps = batch.getJSONObject("parameters").getJSONObject("properties").getJSONObject("steps")
        assertEquals("array", steps.getString("type"))
        assertEquals("object", steps.getJSONObject("items").getString("type"))
        assertTrue(steps.getJSONObject("items").getJSONObject("properties").has("action"))
    }

    // --- ScreenDiff ------------------------------------------------------------------------------

    private val screen = Bounds(0, 0, 1080, 2400)
    private fun snap(vararg nodes: UiNode) =
        ScreenTreeSerializer.serialize(UiNode(bounds = screen, children = nodes.toList()), "com.example.notes", "Notes", screen = screen)
    private fun text(t: String, top: Int) = UiNode(className = "android.widget.TextView", text = t, bounds = Bounds(0, top, 1080, top + 80))
    private fun button(t: String, top: Int) = UiNode(className = "android.widget.Button", text = t, clickable = true, bounds = Bounds(0, top, 500, top + 80))

    @Test
    fun deltaListsOnlyChanges() {
        val rows = (0 until 10).map { text("Row $it", 300 + it * 100) }.toTypedArray()
        val old = snap(button("Add", 100), *rows, text("Loading…", 1400))
        val new = snap(button("Add", 100), text("New row", 250), *rows)
        val delta = ScreenDiff.delta(old, new)!!
        assertTrue(delta, delta.contains("+1 −1 ~0"))
        assertTrue(delta, delta.contains("+ [e2] Text \"New row\""))
        assertTrue(delta, delta.contains("− Text \"Loading…\""))
        assertFalse(delta, delta.contains("Row 5"))
    }

    @Test
    fun deltaFallsBackToFullReading() {
        val old = snap(button("A", 100), button("B", 200))
        val mostlyNew = snap(button("C", 100), button("D", 200))
        assertNull(ScreenDiff.delta(old, mostlyNew))
        assertNull(ScreenDiff.delta(null, old))
        val otherApp = ScreenTreeSerializer.serialize(UiNode(bounds = screen, children = listOf(button("A", 100))), "com.other", screen = screen)
        assertNull(ScreenDiff.delta(old, otherApp))
        assertTrue(ScreenDiff.delta(old, old)!!.contains("no visible change"))
    }

    // --- helpers --------------------------------------------------------------------------------

    @Test
    fun approvalActionsFirstDecisionWins() {
        var result: Boolean? = null
        ApprovalActions.register("c1") { result = it }
        assertTrue(ApprovalActions.decide("c1", true))
        assertFalse(ApprovalActions.decide("c1", false))
        assertEquals(true, result)
        assertFalse(ApprovalActions.decide("unknown", true))
    }

    @Test
    fun writeChunksKeepSurrogatePairsTogether() {
        val emoji = "😀"
        val text = "a".repeat(9) + emoji + "b".repeat(10)
        val chunks = TermuxBridgeClient.chunkForTransfer(text, chunkChars = 10)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { c -> assertFalse(c.isNotEmpty() && Character.isHighSurrogate(c.last())) }
        assertEquals(listOf("short"), TermuxBridgeClient.chunkForTransfer("short"))
    }

    @Test
    fun textInputVerificationAndShizukuEscaping() {
        assertTrue(TextInputStrategy.matches("Hallo Welt", "Hallo Welt", password = false))
        assertTrue(TextInputStrategy.matches("Hallo  Welt\n", "Hallo Welt", password = false))
        assertFalse(TextInputStrategy.matches("Hallo", null, password = false))
        assertFalse(TextInputStrategy.matches("Hallo", "Nachricht", password = false))
        assertTrue(TextInputStrategy.matches("secret", "••••••", password = true))
        assertEquals("hello%sworld", TextInputStrategy.shizukuInputArg("hello world"))
        assertNull(TextInputStrategy.shizukuInputArg("Grüße"))
        assertNull(TextInputStrategy.shizukuInputArg("hi 😀"))
    }

    @Test
    fun fingerprintCoversPackageLabelAndViewId() {
        val a = AndroidPhoneController.fingerprint("com.whatsapp", TargetInfo("Send", viewId = "id/send"))
        assertEquals(a, AndroidPhoneController.fingerprint("com.whatsapp", TargetInfo("Send", viewId = "id/send")))
        assertNotEquals(a, AndroidPhoneController.fingerprint("com.whatsapp", TargetInfo("Delete", viewId = "id/send")))
        assertNotEquals(a, AndroidPhoneController.fingerprint("org.telegram", TargetInfo("Send", viewId = "id/send")))
        assertNull(AndroidPhoneController.fingerprint("com.whatsapp", null))
    }
}

class ScreenLeaseTest {
    @Test
    fun oneRunAtATimeWithIdleExpiry() {
        var now = 0L
        val lease = ScreenLease(idleMs = 1_000L, clock = { now })
        assertNull(lease.acquire("chat"))
        assertNull(lease.acquire("chat"))
        assertTrue(lease.acquire("telegram")!!.startsWith("Screen busy"))
        now = 500L
        assertNotNull(lease.acquire("telegram"))
        lease.release("telegram")
        assertEquals("chat", lease.holder())
        now = 2_000L
        assertNull(lease.acquire("telegram"))
        lease.release("telegram")
        assertNull(lease.holder())
    }
}
