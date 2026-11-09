package com.agent.mobile.phone

/** Screen rectangle in pixels (same coordinate space as gestures). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun isEmpty(): Boolean = width <= 0 || height <= 0
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun area(): Long = if (isEmpty()) 0L else width.toLong() * height.toLong()
}

/**
 * One node of the accessibility tree, copied into plain Kotlin so it can be serialized, matched
 * and tested without Android. [ref] points back to the live node of the capture it came from.
 */
data class UiNode(
    val className: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Password input: its text is never read, logged or sent to the model. */
    val password: Boolean = false,
    val visible: Boolean = true,
    val ref: Int = -1,
    val children: List<UiNode> = emptyList()
) {
    /** Short role name for the model, derived from the widget class. */
    val role: String get() = roleOf(this)

    companion object {
        fun roleOf(node: UiNode): String {
            val cls = node.className.substringAfterLast('.')
            return when {
                node.password -> "PasswordField"
                node.editable || cls.contains("EditText") -> "Field"
                cls.contains("Switch") || cls.contains("Toggle") -> "Switch"
                cls.contains("CheckBox") -> "Checkbox"
                cls.contains("RadioButton") -> "Radio"
                cls.contains("ImageButton") -> "IconButton"
                cls.contains("Button") -> "Button"
                cls.contains("SeekBar") || cls.contains("Slider") -> "Slider"
                cls.contains("Spinner") -> "Dropdown"
                cls.contains("Tab") -> "Tab"
                cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("GridView") ||
                    cls.contains("ScrollView") || cls.contains("ViewPager") || node.scrollable -> "List"
                cls.contains("ImageView") || cls.contains("Image") -> if (node.clickable) "IconButton" else "Image"
                cls.contains("WebView") -> "Web"
                cls.contains("TextView") || cls.contains("Text") -> if (node.clickable) "Link" else "Text"
                node.clickable -> "Item"
                else -> "View"
            }
        }
    }
}

/** An element of a snapshot: a node with a short id the model uses to refer to it. */
data class ScreenElement(
    val id: String,
    val node: UiNode,
    /** Id of the nearest listed ancestor, or null at the top level. */
    val parentId: String?,
    val depth: Int
) {
    /** The best human-readable label: text, then description, then hint. Never a password's content. */
    val label: String
        get() = if (node.password) node.hint.orEmpty().trim()
        else (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() } ?: node.hint).orEmpty().trim()
}

/** The serialized screen: the text sent to the model plus the elements it can refer to. */
data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val elements: List<ScreenElement>,
    val text: String,
    val truncated: Boolean,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val capturedAt: Long = System.currentTimeMillis()
) {
    fun byId(id: String): ScreenElement? = elements.firstOrNull { it.id == id }
}
