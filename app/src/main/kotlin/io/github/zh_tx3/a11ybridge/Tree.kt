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

internal object Tree {
    /** A deep RecyclerView can otherwise eat the whole budget: the caps count nodes, not time. */
    const val MAX_NODES = 2000
    const val MAX_DEPTH = 80
    const val MAX_WINDOWS = 4

    private class State(val deadline: Long, val now: () -> Long) {
        var count = 0
        var truncated = false
    }

    fun read(
        windows: List<WindowSource>,
        screen: Box,
        ownPackage: String,
        budgetMs: Long,
        now: () -> Long = { System.currentTimeMillis() },
    ): Snapshot {
        val state = State(now() + budgetMs, now)
        val chosen = ArrayList<Window>(MAX_WINDOWS)
        var keyboard = false
        var top: String? = null
        var ownInFront = false
        var systemInFront = false
        var hidden: String? = null
        val screenArea = screen.area.coerceAtLeast(1)

        for (window in windows.sortedByDescending { it.layer }) {
            if (window.kind == Kind.KEYBOARD) {
                keyboard = true
                continue
            }
            // Our own overlays, other services' overlays, the split-screen divider: not content.
            if (window.kind != Kind.APP && window.kind != Kind.SYSTEM) continue
            if (chosen.size >= MAX_WINDOWS) break
            val share = window.bounds.intersect(screen).area.toDouble() / screenArea
            // Status bar, navigation bar, a floating ball: small system windows are chrome.
            if (window.kind == Kind.SYSTEM && share < 0.5) continue
            val root = window.root
            if (root == null) {
                // A full-screen window we may not read covers whatever is under it.
                if (window.kind == Kind.APP && share >= 0.8) {
                    if (chosen.isEmpty()) hidden = window.title ?: ""
                    break
                }
                continue
            }
            val pkg = root.pkg
            if (chosen.isEmpty()) {
                top = pkg
                ownInFront = pkg == ownPackage
                systemInFront = window.kind == Kind.SYSTEM
            }
            chosen += Window(window.id, window.kind, pkg, window.title, window.bounds, convert(root, 0, state))
            if (share >= 0.8) break // anything under a full-screen window is covered
        }

        return Snapshot(chosen, screen, top, keyboard, ownInFront, systemInFront, state.truncated, hidden)
    }

    private fun convert(node: NodeSource, depth: Int, state: State): Node? {
        if (!node.visibleToUser) return null
        state.count++
        val children = ArrayList<Node>(node.childCount)
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (state.count >= MAX_NODES || state.now() > state.deadline) {
                    state.truncated = true
                    break
                }
                val child = node.child(i) ?: continue
                convert(child, depth + 1, state)?.let(children::add)
            }
        } else if (node.childCount > 0) {
            state.truncated = true
        }
        return Node(
            className = node.className,
            text = node.text,
            desc = node.desc,
            hint = node.hint,
            viewId = node.viewId,
            bounds = node.bounds,
            clickable = node.clickable,
            longClickable = node.longClickable,
            editable = node.editable,
            scrollable = node.scrollable,
            checkable = node.checkable,
            checked = node.checked,
            selected = node.selected,
            focused = node.focused,
            enabled = node.enabled,
            password = node.password,
            heading = node.heading,
            hintShown = node.hintShown,
            scroll = node.scroll,
            role = node.role,
            state = node.state,
            rangeCurrent = node.rangeCurrent,
            rangeMax = node.rangeMax,
            children = children,
        )
    }

    /** How many nodes a snapshot holds. */
    fun count(snapshot: Snapshot): Int = snapshot.windows.sumOf { w -> w.root?.let { count(it) } ?: 0 }

    private fun count(node: Node): Int = 1 + node.children.sumOf { count(it) }
}
