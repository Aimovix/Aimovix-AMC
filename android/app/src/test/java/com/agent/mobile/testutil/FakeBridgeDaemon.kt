package com.agent.mobile.testutil

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small in-process stand-in for termux-bridge/bridge_daemon.py: authenticates with a token,
 * runs "commands" through [onExecute], keeps files in memory and records every message. Each
 * connection gets its own WebSocket; [dropConnections] simulates a bridge restart.
 */
class FakeBridgeDaemon(
    private val token: String = "test-token",
    /** Returns (stdout, exitCode) for a command, or null to leave it running until interrupted. */
    var onExecute: (String) -> Pair<String, Int>? = { "ok\n" to 0 }
) {
    val server = MockWebServer()
    val received = CopyOnWriteArrayList<JSONObject>()
    val files = ConcurrentHashMap<String, String>()
    val connections = AtomicInteger(0)
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile
    var acceptConnections = true
    /** Commands left running (onExecute returned null), by execution id. */
    val running = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Output a reattached running command finishes with (null: it keeps running). */
    @Volatile
    var finishOnReattach: String? = "finished after reconnect\n"
    @Volatile
    var cwd = "/home"
    val jobs = ConcurrentHashMap<String, String>()

    val host: String get() = server.hostName
    val port: Int get() = server.port

    fun start(): FakeBridgeDaemon {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (!acceptConnections) MockResponse().setResponseCode(503)
                else MockResponse().withWebSocketUpgrade(listener())
        }
        server.start()
        return this
    }

    fun shutdown() {
        sockets.forEach { runCatching { it.cancel() } }
        runCatching { server.shutdown() }
    }

    /** Closes every open connection (like the daemon being restarted). */
    fun dropConnections() {
        // Server-side sockets of MockWebServer have no call to cancel: close them instead.
        sockets.forEach { runCatching { it.close(1001, "bridge restart") } }
        sockets.clear()
    }

    fun executed(): List<String> = received.filter { it.optString("action") == "execute" }.map { it.optString("command") }

    fun interrupts(): List<JSONObject> = received.filter { it.optString("action") == "interrupt" }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            connections.incrementAndGet()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            received += msg
            when (msg.optString("action")) {
                "auth" -> if (msg.optString("token") == token) {
                    val claimed = msg.optJSONArray("execution_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val back = if (msg.optString("claim") == "own") claimed.filter { running.containsKey(it) } else emptyList()
                    webSocket.send(
                        JSONObject().put("type", "auth_ok").put("cwd", "/home")
                            .put("system", JSONObject().put("os", "linux").put("cwd", "/home"))
                            .put("capabilities", org.json.JSONArray().put("concurrent_execute").put("write_append").put("claim_own").put("jobs"))
                            .put("reattached", org.json.JSONArray(back))
                            .toString()
                    )
                    val out = finishOnReattach
                    if (out != null) back.forEach { id ->
                        running.remove(id)
                        webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", out).toString())
                        webSocket.send(JSONObject().put("type", "completed").put("execution_id", id).put("exit_code", 0).toString())
                    }
                } else {
                    webSocket.send(JSONObject().put("type", "auth_fail").put("error", "bad token").toString())
                }
                "ping" -> webSocket.send(JSONObject().put("type", "pong").put("ping_id", msg.optString("ping_id")).toString())
                "execute" -> {
                    val id = msg.optString("execution_id")
                    val command = msg.optString("command")
                    if (command.startsWith("cd ")) {
                        cwd = command.removePrefix("cd ").trim()
                        webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", "Directory changed to: $cwd\n").toString())
                        webSocket.send(JSONObject().put("type", "completed").put("execution_id", id).put("exit_code", 0).put("cwd", cwd).toString())
                        return
                    }
                    val result = onExecute(command)
                    if (result == null) {
                        running[id] = command
                        return
                    }
                    if (result.first.isNotEmpty()) {
                        webSocket.send(JSONObject().put("type", "stdout").put("execution_id", id).put("data", result.first).toString())
                    }
                    webSocket.send(JSONObject().put("type", "completed").put("execution_id", id).put("exit_code", result.second).toString())
                }
                "interrupt" -> {
                    val id = msg.optString("execution_id")
                    webSocket.send(JSONObject().put("type", "interrupted").put("execution_id", id).toString())
                    if (id.isNotEmpty()) {
                        webSocket.send(JSONObject().put("type", "completed").put("execution_id", id).put("exit_code", 130).toString())
                    }
                }
                "read_file", "read_file_base64" -> {
                    val path = msg.optString("path")
                    val reqId = msg.optString("request_id")
                    val content = files[path]
                    if (content == null) {
                        webSocket.send(JSONObject().put("type", "error").put("request_id", reqId).put("path", path).put("error", "No such file: $path").toString())
                    } else if (msg.optString("action") == "read_file") {
                        webSocket.send(JSONObject().put("type", "file_content").put("request_id", reqId).put("path", path).put("content", content).toString())
                    } else {
                        val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
                        webSocket.send(JSONObject().put("type", "file_base64").put("request_id", reqId).put("path", path).put("data", b64).toString())
                    }
                }
                "write_file" -> {
                    val path = msg.optString("path")
                    val content = msg.optString("content")
                    files[path] = if (msg.optString("mode") == "append") files[path].orEmpty() + content else content
                    webSocket.send(JSONObject().put("type", "file_written").put("request_id", msg.optString("request_id")).put("path", path).put("success", true).toString())
                }
                "sys_info" -> webSocket.send(JSONObject().put("type", "sys_info").put("cwd", "/home").toString())
                "job_start", "job_status", "job_stop", "job_list" -> {
                    val reply = JSONObject().put("type", "job_result").put("request_id", msg.optString("request_id")).put("ok", true)
                    when (msg.optString("action")) {
                        "job_start" -> {
                            val jobId = "j${jobs.size + 1}"
                            jobs[jobId] = msg.optString("command")
                            reply.put("job", JSONObject().put("job_id", jobId).put("name", msg.optString("name")).put("state", "running")
                                .put("log", "/home/.amc/jobs/$jobId.log").put("runtime_s", 0))
                        }
                        "job_list" -> reply.put("jobs", org.json.JSONArray(jobs.keys.map { JSONObject().put("job_id", it).put("name", it).put("state", "running") }))
                        else -> {
                            val jobId = msg.optString("job_id")
                            if (!jobs.containsKey(jobId)) {
                                reply.put("ok", false).put("error", "No background job with this id.")
                            } else {
                                val state = if (msg.optString("action") == "job_stop") "stopped" else "done"
                                reply.put("job", JSONObject().put("job_id", jobId).put("name", jobId).put("state", state).put("exit_code", 0)
                                    .put("log", "/home/.amc/jobs/$jobId.log").put("runtime_s", 3).put("new_output", "server ready on :8080\n"))
                            }
                        }
                    }
                    webSocket.send(reply.toString())
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets -= webSocket
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= webSocket
        }
    }
}
