package com.agent.mobile.phone

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType

/** Pure helpers for screenshots: vision support of the model, scaling, protected-window detection. */
object ScreenshotSupport {

    /** Longest side of the image sent to the model; enough to read UI text, small in tokens. */
    const val MAX_SIDE = 1280
    const val JPEG_QUALITY = 70

    private val NO_VISION = Regex(
        """(gpt-3\.5|gpt-4-(0613|0314)|o1-mini|o3-mini|llama-3\.[0-3]|llama3\.[0-3]|llama-2|mixtral|mistral-7b|gemma-?2|qwen2\.5(?!-?vl)|deepseek-(r1|chat|coder)|gpt-oss|phi-?[23](?!.*vision))""",
        RegexOption.IGNORE_CASE
    )
    private val VISION_HINT = Regex("""(vision|-vl|vl-|llava|pixtral|gemma-?3|llama-?4|llama-3\.2-(11|90)b|qwen2?\.?5?-?vl|minicpm-v|moondream)""", RegexOption.IGNORE_CASE)

    /**
     * Best guess whether the configured model accepts images. Gemini and Claude models do;
     * OpenAI's current models do; local and Groq models only when their name says so.
     */
    fun likelySupportsVision(config: ModelConfig): Boolean {
        val model = config.modelName.trim()
        if (VISION_HINT.containsMatchIn(model)) return true
        return when (config.provider) {
            ProviderType.GEMINI, ProviderType.CLAUDE -> true
            ProviderType.OPENAI -> !NO_VISION.containsMatchIn(model)
            ProviderType.OPENROUTER -> model == "openrouter/auto" || !NO_VISION.containsMatchIn(model)
            ProviderType.GROQ, ProviderType.LOCAL -> false
        }
    }

    /** Size to scale a [width]x[height] frame to, keeping the aspect ratio, longest side at most [max]. */
    fun targetSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val scale = max.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /**
     * True when the app area (between status bar and navigation bar) is completely black, which
     * is how Android renders windows with FLAG_SECURE in screenshots. Such frames are not sent.
     * [pixels] are ARGB values in row-major order.
     */
    fun isProtectedFrame(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        val top = (height * 0.08).toInt()
        val bottom = (height * 0.92).toInt()
        if (bottom <= top) return false
        val stepY = maxOf(1, (bottom - top) / 64)
        val stepX = maxOf(1, width / 64)
        var y = top
        while (y < bottom) {
            var x = 0
            while (x < width) {
                if (pixels[y * width + x] and 0x00FFFFFF != 0) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }
}
