package io.github.zh_tx3.a11ybridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeJsonTest {
    private val screen = Box(0, 0, 400, 800)

    private fun snapshot(
        vararg windows: Window,
        pkg: String? = "example.app",
        keyboard: Boolean = false,
        ownInFront: Boolean = false,
        systemInFront: Boolean = false,
        truncated: Boolean = false,
        hidden: String? = null,
    ) = Snapshot(windows.toList(), screen, pkg, keyboard, ownInFront, systemInFront, truncated, hidden)

    private fun window(vararg nodes: Node) =
        Window(1, Kind.APP, "example.app", null, Box(0, 0, 400, 800), Node(bounds = Box(0, 0, 400, 800), children = nodes.toList()))

    private fun json(snapshot: Snapshot, seq: Int = 7, ms: Long = 12) =
        TreeJson.stringify(snapshot, seq, ms, at = 1_000)

    @Test
    fun theHeaderCarriesSchemaSeqTimingAndCounts() {
        val out = json(snapshot(window(Node(bounds = Box(0, 0, 400, 800)))))
        assertTrue(out.startsWith("{\"schema\":\"a11y-bridge/1\",\"seq\":7,\"at\":1000,\"capture_ms\":12"))
        assertTrue(out.contains("\"screen\":[0,0,400,800]"))
        assertTrue(out.contains("\"pkg\":\"example.app\""))
        assertTrue(out.endsWith("\"nodes\":2}")) // the window's root plus the node in it
    }

    @Test
    fun aNodeEmitsOnlyWhatItHas() {
        val button = Node(className = "android.widget.Button", text = "确定",
            bounds = Box(812, 1990, 1032, 2100), clickable = true, focused = true)
        val plain = Node(bounds = Box(0, 0, 10, 10))
        val out = json(snapshot(window(button, plain)))

        assertTrue(out.contains("{\"i\":1,\"d\":1,\"cls\":\"android.widget.Button\",\"text\":\"确定\"," +
            "\"b\":[812,1990,1032,2100],\"flags\":[\"clickable\",\"focused\"],\"children\":[]}"))
        // No class, no text, no flags: nothing but the index, bounds and children.
        assertTrue(out.contains("{\"i\":2,\"d\":1,\"b\":[0,0,10,10],\"children\":[]}"))
    }

    @Test
    fun nodeIndicesAreDepthFirstFromZero() {
        val deep = Node(bounds = Box(0, 0, 10, 10), children = listOf(Node(bounds = Box(0, 0, 5, 5))))
        val out = json(snapshot(window(deep)))
        assertTrue(out.contains("{\"i\":0,"))  // the window root
        assertTrue(out.contains("{\"i\":1,\"d\":1,"))
        assertTrue(out.contains("{\"i\":2,\"d\":2,"))
    }

    @Test
    fun disabledIsReportedNeverEnabled() {
        val out = json(snapshot(window(Node(bounds = Box(0, 0, 10, 10), enabled = false))))
        assertTrue(out.contains("\"flags\":[\"disabled\"]"))
        assertFalse(out.contains("\"enabled\""))
    }

    @Test
    fun scrollDirectionsRoleStateAndRangeShowUp() {
        val list = Node(className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Box(0, 100, 400, 700), scrollable = true,
            scroll = Scroll.FORWARD or Scroll.DOWN, role = "list", state = "3 of 9",
            rangeCurrent = 3f, rangeMax = 10f)
        val out = json(snapshot(window(list)))
        assertTrue(out.contains("\"scroll\":[\"forward\",\"down\"]"))
        assertTrue(out.contains("\"role\":\"list\""))
        assertTrue(out.contains("\"state\":\"3 of 9\""))
        assertTrue(out.contains("\"range\":[3.0,10.0]"))
    }

    @Test
    fun textIsEscaped() {
        val out = json(snapshot(window(Node(bounds = Box(0, 0, 10, 10), text = "a\"b\\c\nd\te"))))
        assertTrue(out.contains("\"text\":\"a\\\"b\\\\c\\nd\\te\""))
    }

    @Test
    fun controlCharactersAreEscapedAsUnicode() {
        val out = json(snapshot(window(Node(bounds = Box(0, 0, 10, 10), text = "\u0001"))))
        assertTrue(out.contains("\"text\":\"\\u0001\""))
    }

    @Test
    fun anUnreadableWindowKeepsItsPlaceWithANullRoot() {
        val system = Window(2, Kind.SYSTEM, null, "通知栏", Box(0, 0, 400, 200), null)
        val out = json(snapshot(window(), system, hidden = "WLAN"))
        assertTrue(out.contains("\"kind\":\"system\""))
        assertTrue(out.contains("\"title\":\"通知栏\""))
        assertTrue(out.contains("\"root\":null"))
        assertTrue(out.contains("\"hidden_window\":\"WLAN\""))
    }

    @Test
    fun theForkFieldNameIsKeptAlongsideTheNewOne() {
        val out = json(snapshot(window(), ownInFront = true))
        assertTrue(out.contains("\"own_in_front\":true"))
        assertTrue(out.contains("\"dsh_in_front\":true")) // clients written against the .a11y fork
    }

    @Test
    fun theCountMatchesTheNodesWritten() {
        val counted = snapshot(window(Node(bounds = Box(0, 0, 10, 10)), Node(bounds = Box(0, 0, 10, 10))))
        assertEquals(Tree.count(counted), 3)
        assertTrue(json(counted).endsWith("\"nodes\":3}"))
    }
}
