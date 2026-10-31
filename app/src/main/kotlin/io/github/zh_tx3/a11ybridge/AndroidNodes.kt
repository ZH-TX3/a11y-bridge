package io.github.zh_tx3.a11ybridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The only place the platform types appear. Everything a node reports is a field of the snapshot
 * the system already handed us; [child] is the single call that goes back for more, which is why
 * [Tree] counts nodes rather than time per node.
 */
internal class A11yNode(private val node: AccessibilityNodeInfo) : NodeSource() {
    private val rect = Rect()

    override val className: String get() = node.className?.toString().orEmpty()
    override val text: String? get() = node.text?.toString()
    override val desc: String? get() = node.contentDescription?.toString()
    override val hint: String? get() = node.hintText?.toString()
    override val viewId: String? get() = node.viewIdResourceName
    override val pkg: String? get() = node.packageName?.toString()

    override val bounds: Box
        get() {
            node.getBoundsInScreen(rect)
            return Box(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val visibleToUser: Boolean get() = node.isVisibleToUser
    override val clickable: Boolean get() = node.isClickable
    override val longClickable: Boolean get() = node.isLongClickable
    override val editable: Boolean get() = node.isEditable
    override val scrollable: Boolean get() = node.isScrollable
    override val checkable: Boolean get() = node.isCheckable
    @Suppress("DEPRECATION")
    override val checked: Boolean get() = node.isChecked
    override val selected: Boolean get() = node.isSelected
    override val focused: Boolean get() = node.isFocused
    override val enabled: Boolean get() = node.isEnabled
    override val password: Boolean get() = node.isPassword
    override val heading: Boolean get() = node.isHeading
    override val hintShown: Boolean get() = node.isShowingHintText

    /** What the node can scroll *now*: only the actions it lists. */
    override val scroll: Int
        get() {
            if (!node.isScrollable) return 0
            var bits = 0
            for (action in node.actionList) {
                bits = bits or when (action.id) {
                    AccessibilityAction.ACTION_SCROLL_FORWARD.id -> Scroll.FORWARD
                    AccessibilityAction.ACTION_SCROLL_BACKWARD.id -> Scroll.BACKWARD
                    AccessibilityAction.ACTION_SCROLL_UP.id -> Scroll.UP
                    AccessibilityAction.ACTION_SCROLL_DOWN.id -> Scroll.DOWN
                    AccessibilityAction.ACTION_SCROLL_LEFT.id -> Scroll.LEFT
                    AccessibilityAction.ACTION_SCROLL_RIGHT.id -> Scroll.RIGHT
                    else -> 0
                }
            }
            return bits
        }

    override val role: String?
        get() {
            val extras = try {
                node.extras
            } catch (_: Throwable) {
                null
            } ?: return null
            val raw = extras.getCharSequence("AccessibilityNodeInfo.roleDescription")
                ?: extras.getCharSequence("AccessibilityNodeInfo.chromeRole")
            return raw?.toString()?.lowercase()
        }

    override val state: String? get() = node.stateDescription?.toString()
    override val rangeCurrent: Float? get() = node.rangeInfo?.current
    override val rangeMax: Float? get() = node.rangeInfo?.max
    override val childCount: Int get() = node.childCount

    override fun child(index: Int): NodeSource? =
        try {
            node.getChild(index)?.let { A11yNode(it) }
        } catch (_: Throwable) {
            null
        }
}

/**
 * AccessibilityWindowInfo → WindowSource. [root] falls back to the active window's root when the
 * system answers null for a window that is nevertheless focused (seen on an API 35 Settings page).
 */
internal class A11yWindow(private val window: AccessibilityWindowInfo, private val service: AccessibilityService) : WindowSource() {
    private val rect = Rect()

    override val id: Int get() = window.id

    override val kind: Kind
        get() = when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> Kind.APP
            AccessibilityWindowInfo.TYPE_SYSTEM -> Kind.SYSTEM
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> Kind.KEYBOARD
            else -> Kind.OTHER
        }

    override val layer: Int get() = window.layer
    override val title: String? get() = window.title?.toString()

    override val bounds: Box
        get() {
            window.getBoundsInScreen(rect)
            return Box(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val root: NodeSource?
        get() {
            val direct = window.root
            if (direct != null) return A11yNode(direct)
            if (!window.isActive && !window.isFocused) return null
            val active = service.rootInActiveWindow ?: return null
            return if (active.windowId == window.id) A11yNode(active) else null
        }
}
