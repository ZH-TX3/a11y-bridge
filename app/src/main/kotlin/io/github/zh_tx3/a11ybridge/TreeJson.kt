package io.github.zh_tx3.a11ybridge

/**
 * A [Snapshot] as JSON.
 *
 * A plain function of the snapshot — no Android types — so the wire format is stable and tested
 * (TreeJsonTest). Nothing is reordered or de-duplicated, and every field but `i`, `b`, `children`
 * is optional: a node emits only what it has.
 *
 * ```
 * { "schema": "a11y-bridge/1", "seq": 7, "at": 1761000000000, "capture_ms": 38,
 *   "screen": [0,0,1080,2400], "pkg": "com.android.settings",
 *   "keyboard": false, "own_in_front": false, "system_in_front": false, "truncated": false,
 *   "windows": [ { "id": 12, "kind": "app", "pkg": "…", "title": "…", "bounds": [l,t,r,b],
 *                  "root": <node> } ],
 *   "nodes": 412 }
 * ```
 *
 * `dsh_in_front` repeats `own_in_front` for clients written against the .a11y fork in dsh-Go,
 * which is where this schema started.
 */
internal object TreeJson {
    const val SCHEMA = "a11y-bridge/1"

    /** Recursion guard; [Tree.MAX_DEPTH] already keeps real trees far below it. */
    private const val MAX_DEPTH = 200

    fun stringify(snapshot: Snapshot, seq: Int, captureMs: Long, at: Long = System.currentTimeMillis()): String {
        val out = Out()
        val sb = out.sb
        sb.append("{\"schema\":\"").append(SCHEMA).append('"')
        sb.append(",\"seq\":").append(seq)
        sb.append(",\"at\":").append(at)
        sb.append(",\"capture_ms\":").append(captureMs)
        sb.append(",\"screen\":")
        box(out, snapshot.screen)
        snapshot.pkg?.let { sb.append(",\"pkg\":"); string(out, it) }
        sb.append(",\"keyboard\":").append(snapshot.keyboard)
        sb.append(",\"own_in_front\":").append(snapshot.ownInFront)
        sb.append(",\"dsh_in_front\":").append(snapshot.ownInFront)
        sb.append(",\"system_in_front\":").append(snapshot.systemInFront)
        sb.append(",\"truncated\":").append(snapshot.truncated)
        snapshot.hiddenTitle?.let { sb.append(",\"hidden_window\":"); string(out, it) }
        sb.append(",\"windows\":[")
        snapshot.windows.forEachIndexed { index, window ->
            if (index > 0) sb.append(',')
            window(out, window)
        }
        // The total reads better at the end: it is only known once the trees are written.
        sb.append("],\"nodes\":").append(out.nodes)
        sb.append('}')
        return sb.toString()
    }

    private class Out {
        val sb = StringBuilder(1 shl 16)
        var nodes = 0
        val flags = ArrayList<String>(6)
        val scroll = ArrayList<String>(3)
    }

    private fun window(out: Out, w: Window) {
        val sb = out.sb
        sb.append("{\"id\":").append(w.id)
        sb.append(",\"kind\":\"").append(
            when (w.kind) {
                Kind.SYSTEM -> "system"
                Kind.KEYBOARD -> "keyboard"
                else -> "app"
            },
        ).append('"')
        w.pkg?.let { sb.append(",\"pkg\":"); string(out, it) }
        w.title?.let { sb.append(",\"title\":"); string(out, it) }
        sb.append(",\"bounds\":")
        box(out, w.bounds)
        sb.append(",\"root\":")
        val root = w.root
        if (root == null) sb.append("null") else node(out, root, 0)
        sb.append('}')
    }

    private fun node(out: Out, n: Node, depth: Int) {
        val sb = out.sb
        out.nodes++
        sb.append("{\"i\":").append(out.nodes - 1)
        if (depth > 0) sb.append(",\"d\":").append(depth)
        if (n.className.isNotEmpty()) { sb.append(",\"cls\":"); string(out, n.className) }
        n.text?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"text\":"); string(out, it) }
        n.desc?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"desc\":"); string(out, it) }
        n.hint?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"hint\":"); string(out, it) }
        n.viewId?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"rid\":"); string(out, it) }
        sb.append(",\"b\":")
        box(out, n.bounds)

        out.flags.clear()
        if (n.clickable) out.flags += "clickable"
        if (n.longClickable) out.flags += "long_clickable"
        if (n.editable) out.flags += "editable"
        if (n.scrollable) out.flags += "scrollable"
        if (n.checkable) out.flags += "checkable"
        if (n.checked) out.flags += "checked"
        if (n.selected) out.flags += "selected"
        if (n.focused) out.flags += "focused"
        if (!n.enabled) out.flags += "disabled"
        if (n.password) out.flags += "password"
        if (n.heading) out.flags += "heading"
        if (n.hintShown) out.flags += "hint_shown"
        if (out.flags.isNotEmpty()) { sb.append(",\"flags\":"); strings(out, out.flags) }

        out.scroll.clear()
        if (n.scroll and Scroll.FORWARD != 0) out.scroll += "forward"
        if (n.scroll and Scroll.BACKWARD != 0) out.scroll += "backward"
        if (n.scroll and Scroll.UP != 0) out.scroll += "up"
        if (n.scroll and Scroll.DOWN != 0) out.scroll += "down"
        if (n.scroll and Scroll.LEFT != 0) out.scroll += "left"
        if (n.scroll and Scroll.RIGHT != 0) out.scroll += "right"
        if (out.scroll.isNotEmpty()) { sb.append(",\"scroll\":"); strings(out, out.scroll) }

        n.role?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"role\":"); string(out, it) }
        n.state?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"state\":"); string(out, it) }
        if (n.rangeCurrent != null || n.rangeMax != null) {
            sb.append(",\"range\":[")
            if (n.rangeCurrent != null) sb.append(n.rangeCurrent) else sb.append("null")
            sb.append(',')
            if (n.rangeMax != null) sb.append(n.rangeMax) else sb.append("null")
            sb.append(']')
        }

        sb.append(",\"children\":[")
        if (depth < MAX_DEPTH) {
            n.children.forEachIndexed { index, child ->
                if (index > 0) sb.append(',')
                node(out, child, depth + 1)
            }
        }
        sb.append("]}")
    }

    private fun box(out: Out, b: Box) {
        out.sb.append('[').append(b.left).append(',').append(b.top).append(',')
            .append(b.right).append(',').append(b.bottom).append(']')
    }

    private fun strings(out: Out, values: List<String>) {
        val sb = out.sb
        sb.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            string(out, value)
        }
        sb.append(']')
    }

    private fun string(out: Out, value: String) {
        val sb = out.sb
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
