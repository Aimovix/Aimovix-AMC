package com.agent.mobile.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Teil E, E4: replies are read aloud as plain text, in pieces the engine accepts. */
@RunWith(RobolectricTestRunner::class)
class ReadAloudTest {

    @Test
    fun longTextIsSplitAtSentenceEnds() {
        val sentence = "This is one sentence of a long reply. "
        val text = sentence.repeat(300)
        val parts = ReadAloud.chunks(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= ReadAloud.MAX_CHUNK })
        assertTrue("Cut at a sentence end", parts.first().endsWith("."))
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        assertEquals(listOf("Short."), ReadAloud.chunks("Short."))
        assertTrue(ReadAloud.chunks("   ").isEmpty())
    }

    @Test
    fun speaksMarkdownAsPlainTextOnceTheEngineIsReady() {
        val reader = ReadAloud(ApplicationProvider.getApplicationContext<Context>())
        reader.speak("## Result\nThe backup is **done**; see `~/backup`.")
        // Asked before the engine was ready: spoken as soon as it is.
        reader.onInit(TextToSpeech.SUCCESS)
        val spoken = shadowOf(reader.engine).lastSpokenText
        assertEquals("Result\n\nThe backup is done; see ~/backup.", spoken)
        assertTrue(reader.speaking.value)
        reader.stop()
        assertFalse(reader.speaking.value)
        reader.shutdown()
    }
}
