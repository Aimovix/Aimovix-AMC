package com.agent.mobile.testutil

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake OpenAI-compatible provider. Each request takes the next scripted answer (a tool call or
 * a text reply); [fallback] answers when the script is empty. Every request body is recorded.
 */
class ScriptedLlm {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<JSONObject>()
    private val script = ConcurrentLinkedQueue<(JSONObject) -> String>()
    @Volatile
    var fallback: (JSONObject) -> String = { sse(textChunk("Done.")) }

    val baseUrl: String get() = server.url("/v1").toString()

    fun start(): ScriptedLlm {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                requests += body
                val next = script.poll() ?: fallback
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(next(body))
            }
        }
        server.start()
        return this
    }

    fun shutdown() = runCatching { server.shutdown() }

    fun tool(name: String, args: JSONObject, id: String = "call_${script.size}_${System.nanoTime()}") {
        script += { sse(toolChunk(id, name, args)) }
    }

    fun reply(text: String) {
        script += { sse(textChunk(text)) }
    }

    /** A scripted step that decides its answer from the request (e.g. reacts to the last tool result). */
    fun step(answer: (JSONObject) -> String) {
        script += answer
    }

    fun remaining(): Int = script.size

    companion object {
        fun sse(chunk: JSONObject) = "data: $chunk\n\ndata: [DONE]\n\n"

        fun textChunk(text: String): JSONObject =
            JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))

        fun toolChunk(id: String, name: String, args: JSONObject): JSONObject = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", JSONArray().put(
                            JSONObject().put("index", 0).put("id", id).put(
                                "function", JSONObject().put("name", name).put("arguments", args.toString())
                            )
                        )
                    )
                )
            )
        )

        /** The text of the last tool result the model received in [body]. */
        fun lastToolResult(body: JSONObject): String {
            val messages = body.getJSONArray("messages")
            for (i in messages.length() - 1 downTo 0) {
                val m = messages.getJSONObject(i)
                if (m.optString("role") == "tool") return m.optString("content")
            }
            return ""
        }
    }
}
