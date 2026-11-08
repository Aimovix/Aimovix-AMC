package com.agent.mobile.channel.telegram

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** A Telegram account that may talk to this phone's agent. */
data class PairedUser(val id: Long, val name: String, val pairedAt: Long)

/**
 * Persistent state of the Telegram channel: who is paired, which AMC chat session belongs to
 * which Telegram chat, the update offset and the current one-time pairing code.
 */
data class TelegramState(
    val offset: Long = 0,
    val paired: List<PairedUser> = emptyList(),
    val sessions: Map<Long, String> = emptyMap(),
    val pairingCode: String? = null,
    val pairingExpiresAt: Long = 0,
    val pairingAttempts: Int = 0
) {
    fun isPaired(userId: Long) = paired.any { it.id == userId }

    fun toJson(): String = JSONObject().apply {
        put("offset", offset)
        put("paired", JSONArray().apply {
            paired.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("pairedAt", it.pairedAt)) }
        })
        put("sessions", JSONObject().apply { sessions.forEach { (chat, session) -> put(chat.toString(), session) } })
        pairingCode?.let { put("pairingCode", it) }
        put("pairingExpiresAt", pairingExpiresAt)
        put("pairingAttempts", pairingAttempts)
    }.toString()

    companion object {
        fun fromJson(raw: String?): TelegramState {
            if (raw.isNullOrBlank()) return TelegramState()
            return try {
                val o = JSONObject(raw)
                val pairedArr = o.optJSONArray("paired") ?: JSONArray()
                val paired = (0 until pairedArr.length()).mapNotNull { i ->
                    pairedArr.optJSONObject(i)?.let { PairedUser(it.optLong("id"), it.optString("name"), it.optLong("pairedAt")) }
                }.filter { it.id != 0L }
                val sessionsObj = o.optJSONObject("sessions") ?: JSONObject()
                val sessions = sessionsObj.keys().asSequence().mapNotNull { k ->
                    k.toLongOrNull()?.let { it to sessionsObj.optString(k) }
                }.filter { it.second.isNotEmpty() }.toMap()
                TelegramState(
                    offset = o.optLong("offset", 0),
                    paired = paired,
                    sessions = sessions,
                    pairingCode = o.optString("pairingCode").ifEmpty { null },
                    pairingExpiresAt = o.optLong("pairingExpiresAt", 0),
                    pairingAttempts = o.optInt("pairingAttempts", 0)
                )
            } catch (e: Exception) {
                TelegramState()
            }
        }
    }
}

/** Thread-safe holder of [TelegramState] that persists every change through [save]. */
class TelegramStateStore(
    private val load: () -> String?,
    private val save: (String) -> Unit
) {
    private val lock = Any()
    private var cached: TelegramState? = null

    fun get(): TelegramState = synchronized(lock) {
        cached ?: TelegramState.fromJson(load()).also { cached = it }
    }

    fun update(transform: (TelegramState) -> TelegramState): TelegramState = synchronized(lock) {
        val next = transform(get())
        cached = next
        save(next.toJson())
        next
    }

    /** Forgets the cached copy, e.g. after the settings screen changed the stored state. */
    fun reload() = synchronized(lock) { cached = null }

    companion object {
        const val PAIRING_TTL_MS = 15 * 60_000L
        const val MAX_PAIRING_ATTEMPTS = 5
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        /** 8 characters from a 32-letter alphabet without look-alikes (~10^12 combinations). */
        fun newPairingCode(): String = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }
}
