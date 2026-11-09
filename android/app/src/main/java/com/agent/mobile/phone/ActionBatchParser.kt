package com.agent.mobile.phone

import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the `steps` of a screen_actions call. Tool arguments arrive as strings, so the array is
 * a JSON string here; it is parsed strictly: an unknown action, a missing field or more than
 * [PhoneTools.MAX_BATCH_STEPS] steps reject the whole batch before anything runs.
 */
object ActionBatchParser {

    /** One step: its action and the arguments of the matching single tool. */
    data class Step(val action: String, val args: Map<String, String>, val expect: String?) {
        val toolName: String get() = TOOL_FOR[action] ?: action

        /** The single tool call this step stands for (risk check, description, audit). */
        fun toToolCall(batchCallId: String, index: Int = 0): ToolCall =
            ToolCall(id = "$batchCallId#${index + 1}", name = toolName, arguments = args)
    }

    data class Parsed(val steps: List<Step>, val error: String?)

    private val TOOL_FOR = mapOf(
        "tap" to PhoneTools.SCREEN_TAP,
        "type" to PhoneTools.SCREEN_TYPE,
        "scroll" to PhoneTools.SCREEN_SCROLL,
        "swipe" to PhoneTools.SCREEN_SWIPE,
        "global" to PhoneTools.SCREEN_GLOBAL,
        "wait_for" to PhoneTools.WAIT_FOR
    )

    /** Fields each action may carry (anything else is ignored). */
    private val FIELDS = mapOf(
        "tap" to listOf("target", "x", "y", "long_press"),
        "type" to listOf("text", "target", "submit"),
        "scroll" to listOf("direction", "target"),
        "swipe" to listOf("x1", "y1", "x2", "y2", "duration_ms"),
        "global" to listOf("action"),
        "wait_for" to listOf("text", "timeout_seconds", "gone")
    )

    val ACTIONS: Set<String> get() = TOOL_FOR.keys

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed(emptyList(), "steps is required (a JSON array of steps).")
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return Parsed(emptyList(), "steps must be a JSON array of step objects.")
        }
        if (array.length() == 0) return Parsed(emptyList(), "steps is empty.")
        if (array.length() > PhoneTools.MAX_BATCH_STEPS) {
            return Parsed(emptyList(), "At most ${PhoneTools.MAX_BATCH_STEPS} steps per batch (got ${array.length()}).")
        }
        val steps = mutableListOf<Step>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: return Parsed(emptyList(), "Step ${i + 1} is not an object.")
            val step = parseStep(obj) ?: return Parsed(emptyList(), "Step ${i + 1}: action must be one of ${ACTIONS.joinToString()}.")
            validate(step)?.let { return Parsed(emptyList(), "Step ${i + 1} (${step.action}): $it") }
            steps += step
        }
        return Parsed(steps, null)
    }

    private fun parseStep(obj: JSONObject): Step? {
        val action = obj.optString("action").trim().lowercase()
        val fields = FIELDS[action] ?: return null
        val args = mutableMapOf<String, String>()
        for (f in fields) {
            // "global" steps name the button in "global" (or "key"); the tool calls it "action".
            val source = if (action == "global" && f == "action") listOf("global", "key", "button").firstOrNull { obj.has(it) } else f
            if (source != null && obj.has(source) && !obj.isNull(source)) args[f] = obj.get(source).toString()
        }
        if (action == "wait_for" && !args.containsKey("timeout_seconds")) args["timeout_seconds"] = "5"
        val expect = obj.optString("expect").trim().takeIf { obj.has("expect") && it.isNotEmpty() }
        return Step(action, args, expect)
    }

    private fun validate(step: Step): String? {
        val a = step.args
        fun num(k: String) = a[k]?.trim()?.toDoubleOrNull() != null
        return when (step.action) {
            "tap" -> if (a["target"].isNullOrBlank() && !(num("x") && num("y"))) "needs target or x and y." else null
            "type" -> if (a["text"] == null) "needs text." else null
            "scroll" -> if (a["direction"]?.trim()?.lowercase() !in setOf("up", "down", "left", "right")) "direction must be up, down, left or right." else null
            "swipe" -> if (!listOf("x1", "y1", "x2", "y2").all(::num)) "needs x1, y1, x2 and y2." else null
            "global" -> if (a["action"]?.trim()?.lowercase() !in setOf("back", "home", "recents")) "global must be back, home or recents." else null
            "wait_for" -> if (a["text"].isNullOrBlank()) "needs text." else null
            else -> "unknown action."
        }
    }

    /** The steps with typed text hidden, for storage and display. Invalid input is replaced. */
    fun redactedSteps(raw: String?): String {
        val array = try {
            JSONArray(raw?.trim().orEmpty())
        } catch (e: Exception) {
            return "[]"
        }
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("action").trim().equals("type", ignoreCase = true) && obj.has("text")) {
                obj.put("text", "••••••")
            }
        }
        return array.toString()
    }
}
