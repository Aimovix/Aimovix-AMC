package com.agent.mobile.entry

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Text (and optionally an image) to put into the chat input, never sent on its own. */
data class ChatDraft(val text: String, val imageUri: Uri? = null)

/**
 * Ways into AMC from outside the app (Teil E, E2/E3): "Share to AMC" from other apps, launcher
 * shortcuts and the Quick Settings tile. Shared content only fills the chat input; the user
 * decides what to ask and sends it, so nothing from another app runs by itself.
 */
object EntryIntents {

    const val ACTION_NEW_TASK = "com.agent.mobile.action.NEW_TASK"
    const val ACTION_STOP_ALL = "com.agent.mobile.action.STOP_ALL"

    /** Extras of the intent the relay hands to MainActivity. */
    const val EXTRA_DRAFT_TEXT = "com.agent.mobile.extra.DRAFT_TEXT"
    const val EXTRA_DRAFT_IMAGE = "com.agent.mobile.extra.DRAFT_IMAGE"
    const val EXTRA_NEW_TASK = "com.agent.mobile.extra.NEW_TASK"

    const val MAX_SHARED_CHARS = 20_000

    /** The chat draft of a share intent (text, subject, link or image), or null when it is none. */
    fun draftFromShare(action: String?, type: String?, text: CharSequence?, subject: String?, stream: Uri?): ChatDraft? {
        if (action != Intent.ACTION_SEND) return null
        val body = text?.toString()?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        val combined = when {
            title.isNotEmpty() && body.isNotEmpty() && !body.startsWith(title) -> "$title\n$body"
            body.isNotEmpty() -> body
            else -> title
        }.take(MAX_SHARED_CHARS)
        val image = stream?.takeIf { type?.startsWith("image/") == true }
        if (combined.isEmpty() && image == null) return null
        return ChatDraft(combined, image)
    }

    fun draftFromShare(intent: Intent): ChatDraft? = draftFromShare(
        intent.action, intent.type,
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        intent.getStringExtra(Intent.EXTRA_SUBJECT),
        streamOf(intent)
    )

    /** The draft the relay put on a MainActivity intent, if any. */
    fun draftFromRelay(intent: Intent?): ChatDraft? {
        intent ?: return null
        val text = intent.getStringExtra(EXTRA_DRAFT_TEXT)
        val image = intent.getStringExtra(EXTRA_DRAFT_IMAGE)?.let { Uri.parse(it) }
        if (text == null && image == null) return null
        return ChatDraft(text.orEmpty(), image)
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
