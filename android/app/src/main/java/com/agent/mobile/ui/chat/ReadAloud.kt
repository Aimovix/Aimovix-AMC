package com.agent.mobile.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.agent.mobile.ui.chat.markdown.Markdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reads replies aloud with the phone's text-to-speech engine (Teil E, E4): no network, no extra
 * permission. Markdown marks are removed first; long replies are spoken in pieces the engine
 * accepts. A request made before the engine is ready is spoken once it is.
 */
class ReadAloud(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        /** Below TextToSpeech.getMaxSpeechInputLength() (4000) on every Android version. */
        const val MAX_CHUNK = 3_800

        /** Splits [text] into pieces of at most [max] characters, at sentence or word ends where possible. */
        fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
            val out = mutableListOf<String>()
            var rest = text.trim()
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val cut = listOf(window.lastIndexOf(". "), window.lastIndexOf("\n"), window.lastIndexOf("! "), window.lastIndexOf("? "))
                    .maxOrNull()?.takeIf { it > max / 2 }?.plus(1)
                    ?: window.lastIndexOf(' ').takeIf { it > max / 2 }
                    ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }

    private val appContext = context.applicationContext
    private var created = false
    /** Bound only when something is read for the first time, not when the chat opens. */
    private val tts: TextToSpeech by lazy {
        created = true
        TextToSpeech(appContext, this).also {
            it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith("-last") == true) _speaking.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _speaking.value = false }
            })
        }
    }
    private var ready = false
    private var pending: String? = null
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** The engine, for tests. */
    internal val engine: TextToSpeech get() = tts

    private fun ensureEngine(): TextToSpeech = tts

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending?.let { pending = null; speak(it) }
    }

    /** Speaks a reply (Markdown is read as plain text). */
    fun speak(markdown: String) {
        val text = Markdown.plain(markdown)
        if (text.isBlank()) return
        if (!ready) {
            pending = markdown
            ensureEngine() // onInit speaks the pending text
            return
        }
        if (tts.voice == null) runCatching { tts.language = Locale.getDefault() }
        val parts = chunks(text)
        parts.forEachIndexed { i, part ->
            val id = "amc-$i" + if (i == parts.lastIndex) "-last" else ""
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        _speaking.value = true
    }

    fun stop() {
        pending = null
        if (created) tts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        if (created) tts.shutdown()
    }
}
