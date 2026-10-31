package io.github.zh_tx3.a11ybridge

/**
 * Reading the screen, with the Android types pushed behind [NodeSource] / [WindowSource] so the
 * rules — which windows count, what a full-screen window hides, the caps — are plain Kotlin and
 * covered by JVM tests (TreeTest).
 */

/** Screen rectangle in pixels: left/top inclusive, right/bottom exclusive. */
internal class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area: Long get() = if (width <= 0 || height <= 0) 0L else width.toLong() * height

    fun intersect(other: Box) =
        Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))

    override fun toString() = "$left,$top,$right,$bottom"
}

/** Which ways a scrollable node can move right now (from its action list). */
internal object Scroll {
    const val FORWARD = 1
    const val BACKWARD = 2
    const val UP = 4
    const val DOWN = 8
    const val LEFT = 16
    const val RIGHT = 32
}

/** One node of a capture. Everything is what the platform gave us; nothing is inferred. */
internal class Node(
    val className: String = "",
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: Box,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val heading: Boolean = false,
    val hintShown: Boolean = false,
    /** [Scroll] bits. */
    val scroll: Int = 0,
    /** Chromium's chromeRole / Compose's role description, lower-cased. */
    val role: String? = null,
    /** API 30 stateDescription: 开/关 on switches Compose draws itself. */
    val state: String? = null,
    val rangeCurrent: Float? = null,
    val rangeMax: Float? = null,
    val children: List<Node> = emptyList(),
)

internal enum class Kind { APP, SYSTEM, KEYBOARD, OTHER }

/** One window the read looked at. */
internal class Window(
    val id: Int,
    val kind: Kind,
    val pkg: String?,
    val title: String?,
    val bounds: Box,
    val root: Node?,
)

internal class Snapshot(
    /** Top-most first. */
    val windows: List<Window>,
    val screen: Box,
    /** Package of the top-most readable window: what the person is looking at. */
    val pkg: String?,
    val keyboard: Boolean,
    /** This app's own window is in front. */
    val ownInFront: Boolean,
    /** The notification shade or an OEM system window covers the screen. */
    val systemInFront: Boolean,
    /** Hit the node or time cap: the tree may be incomplete. */
    val truncated: Boolean,
    /** Title of a front window whose content the system refuses to hand over (null root). */
    val hiddenTitle: String? = null,
)

/**
 * What [Tree] needs from a node. Abstract with defaults rather than an interface: the adapter
 * overrides all of it, and the test fakes override only what a case is about.
 */
internal abstract class NodeSource {
    open val className: String get() = ""
    open val text: String? get() = null
    open val desc: String? get() = null
    open val hint: String? get() = null
    open val viewId: String? get() = null
    open val pkg: String? get() = null
    open val bounds: Box get() = Box(0, 0, 0, 0)
    open val visibleToUser: Boolean get() = true
    open val clickable: Boolean get() = false
    open val longClickable: Boolean get() = false
    open val editable: Boolean get() = false
    open val scrollable: Boolean get() = false
    open val checkable: Boolean get() = false
    open val checked: Boolean get() = false
    open val selected: Boolean get() = false
    open val focused: Boolean get() = false
    open val enabled: Boolean get() = true
    open val password: Boolean get() = false
    open val heading: Boolean get() = false
    open val hintShown: Boolean get() = false

    /** [Scroll] bits; the adapter builds them from the node's action list. */
    open val scroll: Int get() = 0
    open val role: String? get() = null
    open val state: String? get() = null
    open val rangeCurrent: Float? get() = null
    open val rangeMax: Float? get() = null
    abstract val childCount: Int

    abstract fun child(index: Int): NodeSource?
}

/** What [Tree] needs from a window. [root] is already resolved by the adapter. */
internal abstract class WindowSource {
    abstract val id: Int
    open val kind: Kind get() = Kind.APP

    /** Ordering only: higher is in front. */
    abstract val layer: Int
    open val bounds: Box get() = Box(0, 0, 0, 0)
    open val title: String? get() = null
    abstract val root: NodeSource?
}
