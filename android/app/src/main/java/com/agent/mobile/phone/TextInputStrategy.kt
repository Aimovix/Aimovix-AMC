package com.agent.mobile.phone

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Puts text into an input field so that the app really sees it. Plain ACTION_SET_TEXT is not
 * enough for many apps:
 *
 * 1. Focus first (a click opens the keyboard; Compose, Flutter and WebView fields need it), then
 *    take the node that really has input focus.
 * 2. ACTION_SET_TEXT, re-read the node and compare. Move the cursor to the end so the keyboard
 *    does not write its composing text over it.
 * 3. Clipboard + ACTION_PASTE: the way for WebView and React fields, where SET_TEXT does not fire
 *    onChange and the send button stays disabled. The previous clip is restored (or cleared).
 * 4. Shizuku: `input text` for plain ASCII, or the paste key for everything else.
 *
 * Each stage is only tried when the previous one could not be verified. The result says which
 * stage worked, or what failed, so the model and the user get a useful diagnosis.
 */
class TextInputStrategy(
    private val service: PhoneControlService,
    private val shizukuReady: () -> Boolean = { ShizukuShell.status(service) == ShizukuShell.Status.READY }
) {
    data class Result(val ok: Boolean, val method: String, val diagnostic: String)

    companion object {
        private const val TAG = "TextInputStrategy"
        private const val FOCUS_WAIT_MS = 600L

        /** True when [actual] (read back from the field) shows that [expected] was entered. */
        fun matches(expected: String, actual: CharSequence?, password: Boolean): Boolean {
            if (password) return actual != null && actual.length == expected.length
            val a = actual?.toString() ?: return expected.isEmpty()
            if (a == expected) return true
            // Some fields normalize whitespace or line breaks.
            fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
            return norm(a) == norm(expected)
        }

        /** `input text` only handles printable ASCII; spaces must be %s, shell specials escaped. */
        fun shizukuInputArg(text: String): String? {
            if (text.isEmpty() || text.any { it.code !in 0x20..0x7e }) return null
            return buildString {
                for (c in text) {
                    when (c) {
                        ' ' -> append("%s")
                        '%' -> append("\\%")
                        else -> append(c)
                    }
                }
            }
        }
    }

    suspend fun enter(target: AccessibilityNodeInfo, text: String): Result {
        val password = target.isPassword
        val node = focus(target)
        val stages = mutableListOf<String>()

        // Stage 1: ACTION_SET_TEXT with verification.
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        // A password field cannot be read back reliably (masked or null); trust the action there.
        if (setOk && (password || verify(node, text, password))) {
            moveCursorToEnd(node, text.length)
            return Result(true, "set_text", "")
        }
        stages += if (setOk) "set_text: accepted but the field shows something else" else "set_text: refused"

        // Stage 2: clipboard paste. Never for passwords: other apps could read the clipboard.
        if (!password) {
            val pasted = paste(node, text)
            if (pasted && verify(node, text, password)) return Result(true, "paste", "")
            stages += if (pasted) "paste: accepted but not verified" else "paste: refused"
        }

        // Stage 3: Shizuku key input.
        if (shizukuReady()) {
            val ok = shizukuType(node, text)
            if (ok && verify(node, text, password)) return Result(true, "shizuku", "")
            stages += if (ok) "shizuku: sent but not verified" else "shizuku: failed"
        } else stages += "shizuku: not available"

        val diagnostic = "Text input failed (${stages.joinToString("; ")}). Field: ${node.className}, " +
            "editable=${node.isEditable}, focused=${node.isFocused}, enabled=${node.isEnabled}, " +
            "showsHint=${if (Build.VERSION.SDK_INT >= 26) node.isShowingHintText else "?"}."
        return Result(false, "none", diagnostic)
    }

    /** Focuses [target] (click, else focus action) and returns the node that has input focus. */
    private suspend fun focus(target: AccessibilityNodeInfo): AccessibilityNodeInfo {
        target.refresh()
        if (target.isFocused) return target
        val clicked = target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val deadline = SystemClock.uptimeMillis() + FOCUS_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
            target.refresh()
            if (target.isFocused) return target
            delay(50)
        }
        return target
    }

    private suspend fun verify(node: AccessibilityNodeInfo, text: String, password: Boolean): Boolean {
        // Give the app a moment to apply the change (onTextChanged, recomposition).
        repeat(3) {
            delay(60)
            node.refresh()
            val showsHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
            val current = if (showsHint) null else node.text
            if (matches(text, current, password)) return true
        }
        return false
    }

    private fun moveCursorToEnd(node: AccessibilityNodeInfo, length: Int) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not move the cursor: ${e.message}")
        }
    }

    /** Replaces the field's content with [text] through the clipboard, then restores the old clip. */
    private suspend fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        // Background apps usually cannot read the clipboard (Android 10+); then it is cleared afterwards.
        val previous = try {
            clipboard.primaryClip
        } catch (e: Exception) {
            null
        }
        return try {
            clipboard.setPrimaryClip(sensitiveClip(text))
            // Select the existing content so the paste replaces it.
            val existing = node.text?.length ?: 0
            if (existing > 0) {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existing)
                })
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(120)
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Paste failed: ${e.message}")
            false
        } finally {
            restoreClipboard(clipboard, previous)
        }
    }

    private suspend fun shizukuType(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            // Clear the field first so the keys are not added to old content.
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })
            val arg = shizukuInputArg(text)
            if (arg == null && node.isPassword) return false
            if (arg != null) {
                ShizukuShell.run(service, listOf("input", "text", arg), timeoutMs = 10_000L).exitCode == 0
            } else {
                // Umlauts, ß and emoji: `input text` cannot type them; paste with the key event.
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
                val previous = try { clipboard.primaryClip } catch (e: Exception) { null }
                try {
                    clipboard.setPrimaryClip(sensitiveClip(text))
                    ShizukuShell.run(service, listOf("input", "keyevent", "279"), timeoutMs = 10_000L).exitCode == 0
                } finally {
                    delay(150)
                    restoreClipboard(clipboard, previous)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku input failed: ${e.message}")
            false
        }
    }

    private fun sensitiveClip(text: String): ClipData = ClipData.newPlainText("AMC", text).apply {
        // Keeps the text out of the clipboard preview and keyboard suggestions (Android 13+).
        description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }

    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        try {
            if (previous != null) clipboard.setPrimaryClip(previous)
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore the clipboard: ${e.message}")
        }
    }
}
