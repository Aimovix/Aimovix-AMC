package com.agent.mobile.channel.telegram

import android.util.Log
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.AttachmentSink
import com.agent.mobile.channel.ChannelAgent
import com.agent.mobile.channel.TurnRequest
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.SecurityAssessment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets the owner talk to the phone's agent from Telegram, like the messaging gateways of
 * OpenClaw or OpenCrabs, but running on the phone itself (long polling, no server).
 *
 * - Only private chats with **paired** Telegram accounts are served. Pairing needs a one-time
 *   code created in the app (valid 15 minutes, 5 attempts).
 * - Every Telegram chat continues its own AMC chat session (visible in the app); `/new` starts
 *   a fresh one.
 * - Actions that need approval are sent as Approve/Reject buttons; no answer within
 *   [approvalTimeoutMs] means "rejected".
 * - Messages sent while the agent works are added to the running task (steering); `/stop`
 *   cancels it.
 */
class TelegramGateway(
    private val api: TelegramBotApi,
    private val store: TelegramStateStore,
    private val agentFactory: (chatId: Long) -> ChannelAgent,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val approvalTimeoutMs: Long = 10 * 60_000L,
    private val pollTimeoutSeconds: Int = 50,
    private val onPaired: (PairedUser) -> Unit = {},
    private val onTurnActive: (Boolean) -> Unit = {}
) {
    sealed class Status {
        object Stopped : Status()
        object Starting : Status()
        data class Running(val botUsername: String) : Status()
        data class Error(val message: String, val fatal: Boolean = false) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private class ChatSlot(val agent: ChannelAgent) {
        @Volatile
        var job: Job? = null
    }

    private class PendingApproval(val chatId: Long, val text: String, val decision: CompletableDeferred<Boolean>)

    private val chats = ConcurrentHashMap<Long, ChatSlot>()
    private val approvals = ConcurrentHashMap<String, PendingApproval>()
    private val lastNotice = ConcurrentHashMap<Long, Long>()
    private var pollJob: Job? = null

    @Volatile
    private var botUsername: String = ""

    /**
     * Decides an approval that a background run parked (see ApprovalQueueManager). Returns true
     * when this was the first answer. Set by the channel manager; null: such buttons do nothing.
     */
    @Volatile
    var onParkedDecision: (suspend (approvalId: String, approved: Boolean, remember: Boolean) -> Boolean)? = null

    /** Records the answer to a parked question (ask_user). True for the first answer. */
    var onParkedAnswer: (suspend (approvalId: String, answer: String) -> Boolean)? = null

    /** Open questions: Telegram message id → approval id, and their answer suggestions. */
    private val questionMessages = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val questionOptions = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * Asks every paired account a question of a background run. Answers: a suggestion button, a
     * reply to the message, or /answer <text> for the latest question.
     */
    suspend fun askParkedQuestion(approvalId: String, question: String, options: List<String>) {
        questionOptions[approvalId] = options
        for (user in store.get().paired) {
            try {
                val id = api.sendMessage(
                    user.id, "❓ Question from a background task\n\n${question.take(1_500)}\n\nReply to this message with your answer.",
                    buttons = options.mapIndexed { i, o -> TelegramBotApi.Button(o.take(40), "$QUESTION_PREFIX$approvalId:$i") }
                )
                questionMessages[id] = approvalId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not ask ${user.id}: ${e.message}")
            }
        }
    }

    private suspend fun answerQuestion(chatId: Long, approvalId: String, answer: String) {
        val record = onParkedAnswer ?: return
        val first = try {
            record(approvalId, answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the answer: ${e.message}")
            false
        }
        questionMessages.values.removeAll { it == approvalId }
        questionOptions.remove(approvalId)
        send(chatId, if (first) "✅ Thanks, the background task continues with your answer." else "This question was already answered.")
    }

    /** Sends [text] to every paired account (their private chats). */
    suspend fun notifyOwners(text: String) {
        for (user in store.get().paired) {
            try {
                sendReply(user.id, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not notify ${user.id}: ${e.message}")
            }
        }
    }

    /**
     * Asks every paired account to approve an action a background run parked. The answer goes to
     * [onParkedDecision]; the first answer (here, in the notification or in the app) wins.
     */
    suspend fun askParkedApproval(approvalId: String, text: String, allowAlways: Boolean = false) {
        for (user in store.get().paired) {
            try {
                api.sendMessage(
                    user.id, "🔐 Approval needed (background task)\n\n${text.take(1_500)}",
                    buttons = listOfNotNull(
                        TelegramBotApi.Button("✅ Approve", "$PARKED_PREFIX$approvalId:y"),
                        TelegramBotApi.Button("❌ Reject", "$PARKED_PREFIX$approvalId:n"),
                        if (allowAlways) TelegramBotApi.Button("♾️ Always allow", "$PARKED_PREFIX$approvalId:a") else null
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not ask ${user.id}: ${e.message}")
            }
        }
    }

    /** True while the agent works on a message from [chatId]. */
    fun isTurnActive(chatId: Long): Boolean = chats[chatId]?.job?.isActive == true

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { pollLoop() }
    }

    suspend fun stop() {
        pollJob?.cancelAndJoin()
        pollJob = null
        approvals.values.forEach { it.decision.complete(false) }
        approvals.clear()
        chats.values.forEach { slot ->
            val job = slot.job
            if (job == null || !job.isActive) {
                slot.agent.close()
            } else {
                job.invokeOnCompletion { slot.agent.close() }
                job.cancel()
            }
        }
        chats.clear()
        _status.value = Status.Stopped
    }

    // -------------------------------------------------------------------------------------------
    // Polling
    // -------------------------------------------------------------------------------------------

    private suspend fun pollLoop() {
        _status.value = Status.Starting
        var backoffMs = 2_000L
        var initialized = false
        while (currentCoroutineIsActive()) {
            try {
                if (!initialized) {
                    val me = api.getMe()
                    botUsername = me.username
                    try {
                        api.setMyCommands(COMMANDS)
                    } catch (e: IOException) {
                        Log.w(TAG, "setMyCommands failed: ${e.message}")
                    }
                    initialized = true
                    _status.value = Status.Running(me.username)
                }
                val updates = api.getUpdates(store.get().offset, pollTimeoutSeconds)
                backoffMs = 2_000L
                if (_status.value !is Status.Running) _status.value = Status.Running(botUsername)
                for (update in updates) {
                    try {
                        handleUpdate(update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Handling update ${update.updateId} failed: ${e.message}", e)
                    } finally {
                        // Confirmed only after the update was taken on: when AMC is killed in between,
                        // Telegram delivers it again instead of losing the message. A failing update
                        // is still confirmed, so it cannot block the queue.
                        store.update { it.copy(offset = maxOf(it.offset, update.updateId + 1)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramBotApi.ApiException) {
                if (e.code == 401 || e.code == 404) {
                    _status.value = Status.Error("Telegram rejected the bot token. Check it in Settings → Channels.", fatal = true)
                    return
                }
                _status.value = when (e.code) {
                    409 -> Status.Error("Another program is reading this bot's messages (a webhook or a second device). Retrying…")
                    429 -> Status.Error("Telegram rate limit. Retrying shortly…")
                    else -> Status.Error("Telegram error: ${e.message}. Retrying…")
                }
                delay(maxOf(backoffMs, e.retryAfterSeconds * 1_000L))
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            } catch (e: IOException) {
                _status.value = Status.Error("No connection to Telegram. Retrying…")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected gateway error: ${e.message}", e)
                _status.value = Status.Error("Unexpected error: ${e.message}. Retrying…")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean = currentCoroutineContext().isActive

    // -------------------------------------------------------------------------------------------
    // Updates
    // -------------------------------------------------------------------------------------------

    internal suspend fun handleUpdate(update: TelegramBotApi.Update) {
        update.callback?.let {
            handleCallback(it)
            return
        }
        val m = update.message ?: return
        if (m.chatType != "private") return
        val text = m.text.trim()
        if (!store.get().isPaired(m.fromId)) {
            handleUnpaired(m, text)
            return
        }
        // A reply to a question of a background task is its answer, not a new task.
        questionMessages[m.replyToMessageId]?.let { approvalId ->
            if (text.isNotBlank()) {
                answerQuestion(m.chatId, approvalId, text)
                return
            }
        }
        val command = text.takeIf { it.startsWith("/") }
            ?.substringBefore(' ')?.substringBefore('@')?.lowercase()
        if (command == "/answer") {
            val answer = text.substringAfter(' ', "").trim()
            val latest = questionMessages.entries.maxByOrNull { it.key }?.value
            when {
                latest == null -> send(m.chatId, "There is no open question.")
                answer.isEmpty() -> send(m.chatId, "Write /answer followed by your answer.")
                else -> answerQuestion(m.chatId, latest, answer)
            }
            return
        }
        when (command) {
            "/start", "/help" -> send(m.chatId, HELP)
            "/pair" -> send(m.chatId, "✅ This account is already paired.")
            "/new", "/reset" -> {
                if (chats[m.chatId]?.job?.isActive == true) {
                    send(m.chatId, "⏳ A task is still running. Send /stop first.")
                } else {
                    store.update { it.copy(sessions = it.sessions - m.chatId) }
                    send(m.chatId, "🆕 Started a new conversation.")
                }
            }
            "/stop" -> {
                val job = chats[m.chatId]?.job
                if (job?.isActive == true) {
                    job.cancel()
                    send(m.chatId, "🛑 Stopping the current task…")
                } else {
                    send(m.chatId, "Nothing is running.")
                }
            }
            "/status" -> send(m.chatId, statusText(m.chatId))
            "/unpair" -> {
                chats.remove(m.chatId)?.let { slot ->
                    val job = slot.job
                    if (job == null || !job.isActive) slot.agent.close() else {
                        job.invokeOnCompletion { slot.agent.close() }
                        job.cancel()
                    }
                }
                store.update { st -> st.copy(paired = st.paired.filterNot { it.id == m.fromId }, sessions = st.sessions - m.chatId) }
                send(m.chatId, "🔓 This Telegram account is no longer paired with AMC.")
            }
            else -> handleTask(m, text)
        }
    }

    private suspend fun handleUnpaired(m: TelegramBotApi.Message, text: String) {
        val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val command = parts.firstOrNull()?.substringBefore('@')?.lowercase()
        if ((command == "/pair" || command == "/start") && parts.size >= 2) {
            send(m.chatId, tryPair(m, parts[1]))
            return
        }
        val now = clock()
        if (now - (lastNotice[m.chatId] ?: 0L) > NOTICE_INTERVAL_MS) {
            lastNotice[m.chatId] = now
            send(
                m.chatId,
                "🔒 This is a private AMC agent. To pair your Telegram account, open AMC → Settings → Channels → " +
                    "Pair account on your phone and send the code here as /pair CODE."
            )
        }
    }

    internal fun tryPair(m: TelegramBotApi.Message, rawCode: String): String {
        val code = rawCode.uppercase().replace("-", "").replace(" ", "")
        val now = clock()
        val st = store.get()
        val expected = st.pairingCode
        if (expected == null || now > st.pairingExpiresAt) {
            return "⌛ There is no active pairing code. Create a new one in AMC → Settings → Channels."
        }
        if (code != expected) {
            val attempts = st.pairingAttempts + 1
            return if (attempts >= TelegramStateStore.MAX_PAIRING_ATTEMPTS) {
                store.update { it.copy(pairingCode = null, pairingExpiresAt = 0, pairingAttempts = 0) }
                "❌ Wrong code. Too many attempts: the code is no longer valid. Create a new one in the app."
            } else {
                store.update { it.copy(pairingAttempts = attempts) }
                "❌ Wrong code."
            }
        }
        val user = PairedUser(m.fromId, m.fromName.ifBlank { "Telegram user" }, now)
        store.update { s ->
            s.copy(
                paired = s.paired.filterNot { it.id == user.id } + user,
                pairingCode = null,
                pairingExpiresAt = 0,
                pairingAttempts = 0
            )
        }
        try {
            onPaired(user)
        } catch (e: Exception) {
            Log.w(TAG, "onPaired callback failed: ${e.message}")
        }
        return "✅ Paired! Hi ${user.name}, I'm the AMC agent on your phone. Tell me what to do.\n\n$HELP"
    }

    private suspend fun handleTask(m: TelegramBotApi.Message, text: String) {
        val slot = chats.getOrPut(m.chatId) { ChatSlot(agentFactory(m.chatId)) }
        if (slot.job?.isActive == true) {
            if (text.isNotBlank() && slot.agent.steer(text)) {
                send(m.chatId, "📝 Got it, I'll take that into account in the running task.")
            } else {
                send(m.chatId, "⏳ I'm still working on the previous task. Send /stop to cancel it.")
            }
            return
        }
        if (text.isBlank() && m.photoFileId == null) {
            if (m.hasUnsupportedMedia) {
                send(m.chatId, "I can read text and photos. Voice messages, videos and files are not supported yet.")
            }
            return
        }
        slot.job = scope.launch { runTurn(m, text, slot) }
    }

    private suspend fun runTurn(m: TelegramBotApi.Message, text: String, slot: ChatSlot) = coroutineScope {
        val chatId = m.chatId
        onTurnActive(true)
        val typing = launch {
            while (isActive) {
                try {
                    api.sendChatAction(chatId, "typing")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cosmetic only.
                }
                delay(4_500L)
            }
        }
        try {
            var imageBase64: String? = null
            m.photoFileId?.let { fileId ->
                try {
                    imageBase64 = Base64.getEncoder().encodeToString(api.downloadFile(fileId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    send(chatId, "⚠️ Could not download the photo: ${e.message}")
                }
            }
            val prompt = text.ifBlank { "(The user sent a photo without a caption. Describe it or ask what to do with it.)" }
            val title = "✈️ " + prompt.lines().first().take(40)
            fun request(text: String, image: String?) = TurnRequest(
                sessionId = store.get().sessions[chatId],
                title = title,
                text = text,
                imageBase64 = image,
                imageMimeType = if (image != null) "image/jpeg" else null,
                approval = { call, assessment -> askApproval(chatId, call, assessment) },
                attachments = AttachmentSink { name, bytes, caption ->
                    api.sendFile(chatId, name, bytes, caption)
                    "Sent $name to the user's Telegram chat."
                }
            )
            var result = slot.agent.run(request(prompt, imageBase64))
            result.sessionId?.let { sid -> store.update { it.copy(sessions = it.sessions + (chatId to sid)) } }
            // A long task that used up the step or time budget continues in the same conversation.
            var part = 1
            while (result.budgetExhausted && result.sessionId != null && part <= MAX_CONTINUATIONS) {
                trySend(chatId, "⏳ Still working (part ${part + 1}): the step or time budget of the last part was used up.")
                result = slot.agent.run(request(CONTINUE_NOTE, null))
                result.sessionId?.let { sid -> store.update { it.copy(sessions = it.sessions + (chatId to sid)) } }
                part++
            }
            typing.cancel()
            val reply = buildString {
                append(result.summary.trim())
                if (result.skippedApprovals.isNotEmpty()) {
                    append("\n\nSkipped (needs approval): ")
                    append(result.skippedApprovals.take(3).joinToString("; ") { it.take(80) })
                }
            }
            sendReply(chatId, reply)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { trySend(chatId, "🛑 Stopped.") }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Telegram turn failed: ${e.message}", e)
            trySend(chatId, "⚠️ Something went wrong: ${e.message}")
        } finally {
            typing.cancel()
            onTurnActive(false)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Approvals
    // -------------------------------------------------------------------------------------------

    internal suspend fun askApproval(chatId: Long, call: ToolCall, assessment: SecurityAssessment): Boolean {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val text = buildString {
            append("🔐 Approval needed\n\n")
            append(AgentTools.approvalPreview(call).take(1_500))
            append("\n\nRisk: ").append(assessment.level.displayName)
            if (assessment.reason.isNotBlank()) append(" – ").append(assessment.reason)
            if (assessment.alwaysConfirm) append("\n⚠️ This affects other people or may cost money.")
        }
        val decision = CompletableDeferred<Boolean>()
        approvals[id] = PendingApproval(chatId, text, decision)
        var messageId: Long? = null
        var lastError: Exception? = null
        // A short network problem must not look like the user saying no.
        for (wait in SEND_RETRY_DELAYS_MS) {
            if (wait > 0) delay(wait)
            try {
                messageId = api.sendMessage(
                    chatId, text,
                    buttons = listOf(
                        TelegramBotApi.Button("✅ Approve", "ap:$id:y"),
                        TelegramBotApi.Button("❌ Reject", "ap:$id:n")
                    )
                )
                break
            } catch (e: CancellationException) {
                approvals.remove(id)
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Could not send approval request: ${e.message}")
            }
        }
        if (messageId == null) {
            approvals.remove(id)
            throw com.agent.mobile.agent.ApprovalUnreachableException(
                "The approval request could not be sent to Telegram (${lastError?.message ?: "network error"})."
            )
        }
        val sentId: Long = messageId
        return try {
            val answer = withTimeoutOrNull(approvalTimeoutMs) { decision.await() }
            if (answer == null) tryEdit(chatId, sentId, "$text\n\n⌛ No answer: not executed.")
            answer ?: false
        } finally {
            approvals.remove(id)
        }
    }

    private suspend fun handleCallback(cb: TelegramBotApi.CallbackQuery) {
        if (cb.data.startsWith(PARKED_PREFIX)) {
            handleParkedCallback(cb)
            return
        }
        if (cb.data.startsWith(QUESTION_PREFIX)) {
            val rest = cb.data.removePrefix(QUESTION_PREFIX)
            val approvalId = rest.substringBeforeLast(':')
            val option = questionOptions[approvalId]?.getOrNull(rest.substringAfterLast(':').toIntOrNull() ?: -1)
            if (option == null || !store.get().isPaired(cb.fromId)) {
                tryAnswer(cb.id, "This question is no longer open.")
                return
            }
            tryAnswer(cb.id, option)
            answerQuestion(cb.chatId, approvalId, option)
            return
        }
        val parts = cb.data.split(':')
        val pending = if (parts.size == 3 && parts[0] == "ap") approvals[parts[1]] else null
        if (pending == null || pending.chatId != cb.chatId || !store.get().isPaired(cb.fromId)) {
            tryAnswer(cb.id, "This request is no longer active.")
            return
        }
        val approved = parts[2] == "y"
        pending.decision.complete(approved)
        tryAnswer(cb.id, if (approved) "Approved" else "Rejected")
        tryEdit(cb.chatId, cb.messageId, pending.text + if (approved) "\n\n✅ Approved" else "\n\n❌ Rejected")
    }

    private suspend fun handleParkedCallback(cb: TelegramBotApi.CallbackQuery) {
        val rest = cb.data.removePrefix(PARKED_PREFIX)
        val id = rest.substringBeforeLast(':')
        val answer = rest.substringAfterLast(':')
        val decide = onParkedDecision
        if (decide == null || id.isBlank() || answer !in setOf("y", "n", "a") || !store.get().isPaired(cb.fromId)) {
            tryAnswer(cb.id, "This request is no longer active.")
            return
        }
        val approved = answer == "y" || answer == "a"
        val first = try {
            decide(id, approved, answer == "a")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the decision: ${e.message}")
            false
        }
        if (!first) {
            tryAnswer(cb.id, "Already decided.")
            return
        }
        tryAnswer(cb.id, if (approved) "Approved" else "Rejected")
        tryEdit(
            cb.chatId, cb.messageId,
            if (approved) "✅ Approved. The background task continues." else "❌ Rejected. The task continues without it."
        )
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private fun statusText(chatId: Long): String {
        val slot = chats[chatId]
        return buildString {
            append("🟢 AMC is online")
            if (botUsername.isNotEmpty()) append(" (@").append(botUsername).append(')')
            append('\n')
            append(if (slot?.job?.isActive == true) "Task: running" else "Task: idle").append('\n')
            val line = try {
                (slot ?: chats.getOrPut(chatId) { ChatSlot(agentFactory(chatId)) }).agent.statusLine()
            } catch (e: Exception) {
                ""
            }
            if (line.isNotBlank()) append(line)
        }.trim()
    }

    private suspend fun sendReply(chatId: Long, text: String) {
        for (chunk in TelegramFormat.split(text)) {
            try {
                api.sendMessage(chatId, TelegramFormat.toHtml(chunk), parseMode = "HTML")
            } catch (e: TelegramBotApi.ApiException) {
                // Formatting rejected (unbalanced tags etc.): send as plain text.
                api.sendMessage(chatId, chunk)
            }
        }
    }

    private suspend fun send(chatId: Long, text: String) {
        try {
            api.sendMessage(chatId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed: ${e.message}")
        }
    }

    private suspend fun trySend(chatId: Long, text: String) = send(chatId, text)

    private suspend fun tryEdit(chatId: Long, messageId: Long, text: String) {
        try {
            api.editMessageText(chatId, messageId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "editMessageText failed: ${e.message}")
        }
    }

    private suspend fun tryAnswer(callbackId: String, text: String) {
        try {
            api.answerCallbackQuery(callbackId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "answerCallbackQuery failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TelegramGateway"
        private const val NOTICE_INTERVAL_MS = 10 * 60_000L
        /** Automatic continuations of a chat task whose budget ran out. */
        const val MAX_CONTINUATIONS = 3
        const val CONTINUE_NOTE = "[AMC] Continuation: the step or time budget of the previous part ended. Look at what " +
            "is already done above and continue the task from there. Do not redo finished steps."

        /** Waits before the attempts to send an approval request. */
        private val SEND_RETRY_DELAYS_MS = listOf(0L, 2_000L, 5_000L)

        /** Callback data prefix of buttons for approvals parked by background runs. */
        const val PARKED_PREFIX = "pk:"
        /** Callback data prefix of answer suggestions for parked questions. */
        const val QUESTION_PREFIX = "pq:"

        val COMMANDS = listOf(
            "new" to "Start a new conversation",
            "stop" to "Stop the running task",
            "status" to "Show agent status",
            "answer" to "Answer the latest question of a background task",
            "help" to "How to use AMC from Telegram",
            "unpair" to "Disconnect this Telegram account"
        )

        val HELP = """
Write what I should do, in any language, e.g. "How full is my phone's storage?" or "Take a photo and send it to me". You can also send a photo with a question.

While I work you can send more instructions. Actions that need your approval come as buttons.

/new – new conversation
/stop – stop the running task
/status – status
/answer <text> – answer the latest question of a background task
/unpair – disconnect this account
""".trim()
    }
}
