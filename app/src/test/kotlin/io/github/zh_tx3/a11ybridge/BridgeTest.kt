package io.github.zh_tx3.a11ybridge

import java.io.InputStream
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The socket path end to end, over real loopback sockets — the part that broke on a phone (a
 * client that vanished mid-line pinned the accept loop) is exactly what these exercise.
 */
class BridgeTest {
    private val token = "tok-abcdefgh"

    @Test
    fun aReadComesBackFramed() {
        withBridge { port, _ ->
            assertEquals("pong-0", request(port, "$token dump"))
        }
    }

    @Test
    fun argumentsReachTheHandler() {
        withBridge { port, seen ->
            request(port, "$token dump 800 reuse 42")
            val single = seen.single()
            assertEquals("dump", single.cmd)
            assertEquals(800, single.budget)
            assertTrue(single.reuse)
            assertEquals(42, single.seq)
        }
    }

    @Test
    fun oneConnectionServesManyReads() {
        withBridge { port, _ ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                repeat(3) { index ->
                    out.write("$token dump\n".toByteArray())
                    out.flush()
                    assertEquals("read $index", "pong-$index", readFrame(input))
                }
            }
        }
    }

    @Test
    fun aWrongTokenGetsNoAnswerAndTheConnectionIsDropped() {
        withBridge { port, _ ->
            assertNull(request(port, "wrong-token dump"))
        }
    }

    @Test
    fun aLineWithoutATokenGetsNoAnswer() {
        withBridge { port, _ ->
            assertNull(request(port, "dump"))
        }
    }

    @Test
    fun aHandlerFailureIsReportedInTheFrameNotByDropping() {
        val bridge = Bridge({ throw IllegalStateException("boom") })
        val port = bridge.start(token, 0).first().port
        try {
            assertEquals("ERR IllegalStateException: boom", request(port, "$token dump"))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun stopReleasesThePort() {
        val bridge = Bridge({ "pong".toByteArray() })
        val port = bridge.start(token, 0).first().port
        assertEquals("pong", request(port, "$token ping"))
        bridge.stop()
        assertNull(request(port, "$token ping"))
    }

    @Test
    fun anIdleListenerClosesItself() {
        val bridge = Bridge({ "pong".toByteArray() }, idleCloseMs = 300, idleCheckMs = 50)
        val port = bridge.start(token, 0).first().port
        assertEquals("pong", request(port, "$token ping"))

        // Silence, then one look: polling with requests would itself keep the listener alive.
        Thread.sleep(1_000)
        assertNull("the port is still answering after the idle window", request(port, "$token ping"))
    }

    @Test
    fun aListenerThatIsUsedStaysOpen() {
        val bridge = Bridge({ "pong".toByteArray() }, idleCloseMs = 400, idleCheckMs = 50)
        val port = bridge.start(token, 0).first().port
        try {
            // Well past the idle window in total, but never idle for it.
            repeat(8) {
                Thread.sleep(80)
                assertEquals("read $it", "pong", request(port, "$token ping"))
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun stoppingIsSafeTwice() {
        val bridge = Bridge({ "pong".toByteArray() })
        bridge.start(token, 0)
        bridge.stop()
        bridge.stop()
        assertTrue(true) // no exception, no leaked thread keeping the JVM up
    }

    private fun withBridge(block: (port: Int, seen: MutableList<Protocol.Request>) -> Unit) {
        val seen = mutableListOf<Protocol.Request>()
        var counter = 0
        val bridge = Bridge({ req ->
            seen += req
            "pong-${counter++}".toByteArray()
        })
        val bound = bridge.start(token, 0)
        assertTrue("nothing bound", bound.isNotEmpty())
        try {
            block(bound.first().port, seen)
        } finally {
            bridge.stop()
        }
    }

    /** One request on its own connection; null when the server answered nothing. */
    private fun request(port: Int, line: String): String? =
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write("$line\n".toByteArray())
                socket.getOutputStream().flush()
                readFrame(socket.getInputStream())
            }
        } catch (_: java.io.IOException) {
            null
        }

    private fun readFrame(input: InputStream): String? {
        val head = readLine(input) ?: return null
        if (!head.startsWith("OK ")) return null
        val size = head.removePrefix("OK ").toIntOrNull() ?: return null
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n < 0) return null
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }
}
