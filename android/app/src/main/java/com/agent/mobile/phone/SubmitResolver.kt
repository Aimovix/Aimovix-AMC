package com.agent.mobile.phone

/**
 * How to submit text that was typed into a field. In messengers the keyboard's action key adds a
 * line break instead of sending, so the send button next to the field is tapped instead.
 */
object SubmitResolver {

    sealed class Plan {
        /** Tap this element (the send button of a messenger). */
        data class TapButton(val button: ScreenElement) : Plan()

        /** Press the keyboard's action key (search fields, forms, single-line inputs). */
        object ImeAction : Plan()

        /** A messenger without a recognizable send button: do not guess. */
        data class NotFound(val reason: String) : Plan()
    }

    /** Decides how to submit [field] in [snapshot] for an app of [kind]. */
    fun plan(snapshot: ScreenSnapshot, field: ScreenElement, kind: AppKind): Plan {
        if (kind != AppKind.MESSAGING) return Plan.ImeAction
        val button = findSendButton(snapshot, field)
            ?: return Plan.NotFound("No send button was found next to the message field.")
        return Plan.TapButton(button)
    }

    /**
     * The send button for [field]: a clickable, enabled element whose label, description or view
     * id means "send" (same rules as the risk check), closest to the field, preferring the same row.
     */
    fun findSendButton(snapshot: ScreenSnapshot, field: ScreenElement): ScreenElement? {
        val fb = field.node.bounds
        val candidates = snapshot.elements.filter { el ->
            el.id != field.id && !el.node.editable && el.node.enabled &&
                isSend(snapshot, el)
        }.map { ElementMatcher.clickTarget(snapshot, it) }
            .filter { it.node.clickable && it.node.enabled && !it.node.editable }
            .distinctBy { it.id }
        if (candidates.isEmpty()) return null
        fun sameRow(b: Bounds) = b.top < fb.bottom + fb.height && fb.top - fb.height < b.bottom
        return candidates.minWithOrNull(
            compareBy<ScreenElement> { if (sameRow(it.node.bounds)) 0 else 1 }
                .thenBy { distance(it.node.bounds, fb) }
        )
    }

    private fun isSend(snapshot: ScreenSnapshot, el: ScreenElement): Boolean {
        val labels = listOfNotNull(el.node.text, el.node.contentDescription, ElementMatcher.effectiveLabel(snapshot, el))
        return labels.any { PhoneToolPolicy.categorize(it, el.node.viewId) == ActionCategory.SEND } ||
            PhoneToolPolicy.categorize(null, el.node.viewId) == ActionCategory.SEND
    }

    /** True when the field looks empty after sending (text gone, or only the hint is shown). */
    fun fieldCleared(field: ScreenElement?, typed: String): Boolean {
        if (field == null) return true
        val n = field.node
        if (n.showingHint) return true
        val text = n.text?.trim().orEmpty()
        if (text.isEmpty()) return true
        // Some apps report the hint as text in an empty field.
        if (!n.hint.isNullOrBlank() && text == n.hint.trim()) return true
        return typed.isNotBlank() && !text.contains(typed.trim().take(40))
    }

    private fun distance(a: Bounds, b: Bounds): Long {
        val dx = (a.centerX - b.centerX).toLong()
        val dy = (a.centerY - b.centerY).toLong()
        return dx * dx + dy * dy
    }
}
