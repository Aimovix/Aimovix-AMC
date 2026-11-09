package com.agent.mobile.phone

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ScreenshotSupportTest {

    private fun model(provider: ProviderType, name: String) = ModelConfig(provider = provider, modelName = name)

    @Test
    fun visionIsAssumedForGeminiClaudeAndCurrentOpenAiModels() {
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.GEMINI, "gemini-2.5-flash")))
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.CLAUDE, "claude-sonnet-5-5")))
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.OPENAI, "gpt-4o-mini")))
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.OPENROUTER, "openrouter/auto")))
    }

    @Test
    fun textOnlyModelsGetNoScreenshots() {
        assertFalse(ScreenshotSupport.likelySupportsVision(model(ProviderType.OPENAI, "gpt-3.5-turbo")))
        assertFalse(ScreenshotSupport.likelySupportsVision(model(ProviderType.GROQ, "llama-3.3-70b-versatile")))
        assertFalse(ScreenshotSupport.likelySupportsVision(model(ProviderType.LOCAL, "qwen2.5:3b")))
        assertFalse(ScreenshotSupport.likelySupportsVision(model(ProviderType.OPENROUTER, "deepseek/deepseek-chat")))
    }

    @Test
    fun visionNamesWinForLocalAndGroqModels() {
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.LOCAL, "llava:7b")))
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.LOCAL, "qwen2.5vl:3b")))
        assertTrue(ScreenshotSupport.likelySupportsVision(model(ProviderType.GROQ, "meta-llama/llama-4-scout-17b-16e-instruct")))
    }

    @Test
    fun scalesTheLongestSideAndKeepsTheAspectRatio() {
        assertEquals(576 to 1280, ScreenshotSupport.targetSize(1080, 2400))
        assertEquals(1280 to 720, ScreenshotSupport.targetSize(1920, 1080))
        assertEquals(800 to 600, ScreenshotSupport.targetSize(800, 600))
        assertEquals(0 to 0, ScreenshotSupport.targetSize(0, 100))
    }

    private fun frame(w: Int, h: Int, fill: Int) = IntArray(w * h) { fill }

    @Test
    fun aBlackAppAreaIsAProtectedWindowEvenWithAVisibleStatusBar() {
        val w = 100
        val h = 200
        val px = frame(w, h, 0xFF000000.toInt())
        // White status bar icons at the top.
        for (x in 10 until 20) px[2 * w + x] = 0xFFFFFFFF.toInt()
        assertTrue(ScreenshotSupport.isProtectedFrame(px, w, h))
    }

    @Test
    fun aDarkScreenWithContentIsNotProtected() {
        val w = 128
        val h = 256
        val px = frame(w, h, 0xFF000000.toInt())
        for (y in 100 until 110) for (x in 0 until w) px[y * w + x] = 0xFFE5E5EA.toInt()
        assertFalse(ScreenshotSupport.isProtectedFrame(px, w, h))
        assertFalse(ScreenshotSupport.isProtectedFrame(frame(w, h, 0xFF1C1C1E.toInt()), w, h))
    }
}
