package com.agent.mobile.channel.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal Telegram Bot API client (long polling, no server or webhook needed). Only the calls
 * AMC uses are implemented. All functions run on [Dispatchers.IO].
 */
class TelegramBotApi(
    private val token: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    /** Thrown for API errors. [code] is Telegram's error_code (401 bad token, 409 conflict, ...). */
    class ApiException(val code: Int, message: String, val retryAfterSeconds: Int = 0) : IOException(message)

    data class BotInfo(val id: Long, val username: String, val firstName: String)

    data class Message(
        val messageId: Long,
        val chatId: Long,
        val chatType: String,
        val fromId: Long,
        val fromName: String,
        val text: String,
        val photoFileId: String? = null,
        val hasUnsupportedMedia: Boolean = false
    )

    data class CallbackQuery(
        val id: String,
        val fromId: Long,
        val chatId: Long,
        val messageId: Long,
        val data: String
    )

    data class Update(val updateId: Long, val message: Message? = null, val callback: CallbackQuery? = null)

    data class Button(val text: String, val callbackData: String)

    private fun url(method: String) = "${baseUrl.trimEnd('/')}/bot$token/$method"

    /** Executes the call so that cancelling the coroutine aborts the HTTP request (long polls included). */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private suspend fun call(method: String, body: RequestBody): JSONObject {
        val request = Request.Builder().url(url(method)).post(body).build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) { response.use { response ->
            val text = response.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(response.code, "Unexpected response from Telegram (HTTP ${response.code}).")
            }
            if (!json.optBoolean("ok", false)) {
                val retryAfter = json.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
                throw ApiException(
                    json.optInt("error_code", response.code),
                    json.optString("description", "Telegram API error"),
                    retryAfter
                )
            }
            json
        } }
    }

    private suspend fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        return call(method, form.build())
    }

    suspend fun getMe(): BotInfo {
        val r = call("getMe").getJSONObject("result")
        return BotInfo(r.getLong("id"), r.optString("username"), r.optString("first_name"))
    }

    /** Long-polls for new updates. Blocks up to [timeoutSeconds] when there are none. */
    suspend fun getUpdates(offset: Long, timeoutSeconds: Int = 50): List<Update> {
        val json = call(
            "getUpdates",
            mapOf(
                "offset" to offset.toString(),
                "timeout" to timeoutSeconds.toString(),
                "allowed_updates" to JSONArray(listOf("message", "callback_query")).toString()
            )
        )
        return parseUpdates(json.optJSONArray("result") ?: JSONArray())
    }

    /** Sends a message and returns its message_id. */
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        buttons: List<Button> = emptyList()
    ): Long {
        val params = mutableMapOf(
            "chat_id" to chatId.toString(),
            "text" to text,
            "disable_web_page_preview" to "true"
        )
        if (parseMode != null) params["parse_mode"] = parseMode
        if (buttons.isNotEmpty()) params["reply_markup"] = inlineKeyboard(buttons).toString()
        return call("sendMessage", params).getJSONObject("result").getLong("message_id")
    }

    suspend fun editMessageText(chatId: Long, messageId: Long, text: String) {
        call(
            "editMessageText",
            mapOf("chat_id" to chatId.toString(), "message_id" to messageId.toString(), "text" to text)
        )
    }

    suspend fun answerCallbackQuery(callbackId: String, text: String = "") {
        call("answerCallbackQuery", mapOf("callback_query_id" to callbackId, "text" to text))
    }

    suspend fun sendChatAction(chatId: Long, action: String = "typing") {
        call("sendChatAction", mapOf("chat_id" to chatId.toString(), "action" to action))
    }

    suspend fun setMyCommands(commands: List<Pair<String, String>>) {
        val arr = JSONArray()
        commands.forEach { (cmd, desc) -> arr.put(JSONObject().put("command", cmd).put("description", desc)) }
        call("setMyCommands", mapOf("commands" to arr.toString()))
    }

    /** Uploads a file; images go out as photos, everything else as documents. */
    suspend fun sendFile(chatId: Long, fileName: String, bytes: ByteArray, caption: String = "") {
        val isPhoto = fileName.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } &&
            bytes.size <= 10 * 1024 * 1024
        val field = if (isPhoto) "photo" else "document"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption.take(1000)) }
            .addFormDataPart(field, fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        call(if (isPhoto) "sendPhoto" else "sendDocument", body)
    }

    /** Downloads a file the user sent (photo), at most [maxBytes]. */
    suspend fun downloadFile(fileId: String, maxBytes: Long = 10L * 1024 * 1024): ByteArray {
        val path = call("getFile", mapOf("file_id" to fileId)).getJSONObject("result").optString("file_path")
        if (path.isEmpty()) throw ApiException(400, "Telegram did not return a file path.")
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/file/bot$token/$path").get().build()
        val fileResponse = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            fileResponse.use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "File download failed (HTTP ${response.code}).")
                val body = response.body ?: throw ApiException(response.code, "Empty file.")
                if (body.contentLength() > maxBytes) throw ApiException(413, "The file is too large.")
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) throw ApiException(413, "The file is too large.")
                source.buffer.readByteArray()
            }
        }
    }

    companion object {
        fun inlineKeyboard(buttons: List<Button>): JSONObject {
            val row = JSONArray()
            buttons.forEach { b -> row.put(JSONObject().put("text", b.text).put("callback_data", b.callbackData)) }
            return JSONObject().put("inline_keyboard", JSONArray().put(row))
        }

        fun parseUpdates(result: JSONArray): List<Update> {
            val out = mutableListOf<Update>()
            for (i in 0 until result.length()) {
                val u = result.optJSONObject(i) ?: continue
                val id = u.optLong("update_id", -1)
                if (id < 0) continue
                val msg = u.optJSONObject("message")?.let { parseMessage(it) }
                val cb = u.optJSONObject("callback_query")?.let { parseCallback(it) }
                out += Update(id, msg, cb)
            }
            return out
        }

        private fun parseMessage(m: JSONObject): Message? {
            val chat = m.optJSONObject("chat") ?: return null
            val from = m.optJSONObject("from") ?: return null
            val photos = m.optJSONArray("photo")
            val largestPhoto = if (photos != null && photos.length() > 0) {
                photos.optJSONObject(photos.length() - 1)?.optString("file_id")?.takeIf { it.isNotEmpty() }
            } else null
            val unsupported = listOf("voice", "audio", "video", "video_note", "document", "sticker", "location", "contact")
                .any { m.has(it) }
            val name = listOf(from.optString("first_name"), from.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { from.optString("username") }
            return Message(
                messageId = m.optLong("message_id"),
                chatId = chat.optLong("id"),
                chatType = chat.optString("type"),
                fromId = from.optLong("id"),
                fromName = name,
                text = m.optString("text").ifEmpty { m.optString("caption") },
                photoFileId = largestPhoto,
                hasUnsupportedMedia = unsupported && largestPhoto == null
            )
        }

        private fun parseCallback(c: JSONObject): CallbackQuery? {
            val from = c.optJSONObject("from") ?: return null
            val message = c.optJSONObject("message")
            return CallbackQuery(
                id = c.optString("id"),
                fromId = from.optLong("id"),
                chatId = message?.optJSONObject("chat")?.optLong("id") ?: from.optLong("id"),
                messageId = message?.optLong("message_id") ?: 0L,
                data = c.optString("data")
            )
        }
    }
}
