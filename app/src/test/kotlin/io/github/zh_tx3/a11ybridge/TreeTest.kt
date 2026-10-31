package io.github.zh_tx3.a11ybridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that decide what a read contains. No Android on the classpath: fakes stand in. */
class TreeTest {
    private val screen = Box(0, 0, 1000, 2000)
    private val own = "io.github.zh_tx3.a11ybridge"

    private class Fake(
        override val className: String = "android.view.View",
        private val label: String? = null,
        override val visibleToUser: Boolean = true,
        override val pkg: String? = "com.example",
        override val bounds: Box = Box(0, 0, 1000, 2000),
        override val clickable: Boolean = false,
        private val kids: List<Fake> = emptyList(),
    ) : NodeSource() {
        override val text: String? get() = label
        override val childCount: Int get() = kids.size
        override fun child(index: Int): NodeSource? = kids.getOrNull(index)
    }

    private class Win(
        override val id: Int,
        override val kind: Kind = Kind.APP,
        override val layer: Int = 1,
        override val bounds: Box = Box(0, 0, 1000, 2000),
        override val title: String? = null,
        override val root: NodeSource? = Fake(),
    ) : WindowSource()

    /** now = 0: the time budget never fires, so the tests are about the caps and the windows. */
    private fun read(windows: List<WindowSource>, ownPackage: String = own) =
        Tree.read(windows, screen, ownPackage, budgetMs = 5_000, now = { 0 })

    @Test
    fun invisibleNodesTakeTheirSubtreeWithThem() {
        val snapshot = read(listOf(Win(1, root = Fake(kids = listOf(Fake(visibleToUser = false, kids = listOf(Fake(label = "hidden"))), Fake(label = "shown"))))))
        val texts = collect(snapshot.windows[0].root!!).filterNotNull()
        assertEquals(listOf("shown"), texts)
    }

    @Test
    fun windowsAreReadTopFirst() {
        // Short windows: a full-screen one hides what is under it (see the next test).
        val short = Box(0, 0, 1000, 200)
        val snapshot = read(
            listOf(
                Win(1, layer = 1, bounds = short),
                Win(2, layer = 3, bounds = short),
                Win(3, layer = 2, bounds = short),
            ),
        )
        assertEquals(listOf(2, 3, 1), snapshot.windows.map { it.id })
    }

    @Test
    fun smallSystemWindowsAreChrome() {
        // A status bar or a floating ball: system window, under half the screen.
        val snapshot = read(listOf(Win(1, kind = Kind.SYSTEM, bounds = Box(0, 0, 1000, 200))))
        assertTrue(snapshot.windows.isEmpty())
        assertFalse(snapshot.systemInFront)
    }

    @Test
    fun aSystemWindowOverHalfTheScreenIsContent() {
        val snapshot = read(listOf(Win(1, kind = Kind.SYSTEM, bounds = Box(0, 0, 1000, 1500))))
        assertEquals(1, snapshot.windows.size)
        assertTrue(snapshot.systemInFront)
        assertEquals(Kind.SYSTEM, snapshot.windows[0].kind)
    }

    @Test
    fun theKeyboardIsAFlagNotAWindow() {
        val snapshot = read(listOf(Win(1, kind = Kind.KEYBOARD, layer = 9), Win(2, kind = Kind.APP)))
        assertTrue(snapshot.keyboard)
        assertEquals(listOf(2), snapshot.windows.map { it.id })
    }

    @Test
    fun otherWindowKindsAreIgnored() {
        val snapshot = read(listOf(Win(1, kind = Kind.OTHER, layer = 9), Win(2)))
        assertEquals(listOf(2), snapshot.windows.map { it.id })
    }

    @Test
    fun aFullScreenWindowHidesWhatIsUnderIt() {
        val snapshot = read(listOf(Win(2, layer = 1), Win(1, layer = 2)))
        assertEquals(listOf(1), snapshot.windows.map { it.id })
    }

    @Test
    fun aWindowThatCoversPartOfTheScreenDoesNotHideTheRest() {
        val snapshot = read(listOf(Win(2, layer = 1), Win(1, layer = 2, bounds = Box(0, 0, 1000, 600))))
        assertEquals(listOf(1, 2), snapshot.windows.map { it.id })
    }

    @Test
    fun anUnreadableFullScreenWindowIsNamed() {
        val snapshot = read(listOf(Win(1, title = "WLAN", root = null)))
        assertTrue(snapshot.windows.isEmpty())
        assertEquals("WLAN", snapshot.hiddenTitle)
    }

    @Test
    fun anUnreadableSmallWindowIsJustSkipped() {
        val snapshot = read(listOf(Win(1, title = "WLAN", bounds = Box(0, 0, 1000, 400), root = null), Win(2, layer = 0)))
        assertEquals(listOf(2), snapshot.windows.map { it.id })
        assertNull(snapshot.hiddenTitle)
    }

    @Test
    fun noMoreThanFourWindows() {
        val snapshot = read((1..6).map { Win(it, layer = it, bounds = Box(0, 0, 1000, 100)) })
        // Small app windows do not cover the screen, so all of them qualify — up to the cap.
        assertEquals(Tree.MAX_WINDOWS, snapshot.windows.size)
    }

    @Test
    fun theNodeCapTruncates() {
        val wide = Fake(kids = (1..Tree.MAX_NODES + 10).map { Fake(label = "n$it") })
        val snapshot = read(listOf(Win(1, root = wide)))
        assertTrue(snapshot.truncated)
        assertTrue(Tree.count(snapshot) <= Tree.MAX_NODES + 1)
    }

    @Test
    fun theDepthCapTruncates() {
        var node = Fake(label = "leaf")
        repeat(Tree.MAX_DEPTH + 5) { node = Fake(kids = listOf(node)) }
        val snapshot = read(listOf(Win(1, root = node)))
        assertTrue(snapshot.truncated)
    }

    @Test
    fun aTypicalTreeIsNotTruncated() {
        val snapshot = read(listOf(Win(1, root = Fake(kids = listOf(Fake(), Fake(kids = listOf(Fake())))))))
        assertFalse(snapshot.truncated)
        assertEquals(4, Tree.count(snapshot))
    }

    @Test
    fun theTopWindowNamesTheScreenAndWhetherItIsOurs() {
        val mine = read(listOf(Win(1, root = Fake(pkg = own))))
        assertEquals(own, mine.pkg)
        assertTrue(mine.ownInFront)

        val theirs = read(listOf(Win(1, root = Fake(pkg = "com.android.settings"))))
        assertEquals("com.android.settings", theirs.pkg)
        assertFalse(theirs.ownInFront)
    }

    private fun collect(node: Node): List<String?> =
        listOf(node.text) + node.children.flatMap { collect(it) }
}
