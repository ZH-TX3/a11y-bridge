package io.github.zh_tx3.a11ybridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val token = "secret-token-1234"

    @Test
    fun aWholeRequestParses() {
        val request = Protocol.parse("$token dump 800 reuse 42", token)
        assertEquals("dump", request?.cmd)
        assertEquals(800, request?.budget)
        assertTrue(request?.reuse == true)
        assertEquals(42, request?.seq)
    }

    @Test
    fun theOptionalsHaveDefaults() {
        val request = Protocol.parse("$token dump", token)
        assertEquals("dump", request?.cmd)
        assertEquals(Protocol.DEFAULT_BUDGET, request?.budget)
        assertFalse(request?.reuse == true)
        assertEquals(0, request?.seq)
    }

    @Test
    fun aDashMeansAbsent() {
        // The client sends a placeholder so the position of seq does not shift.
        val request = Protocol.parse("$token dump 400 - 9", token)
        assertEquals(400, request?.budget)
        assertFalse(request?.reuse == true)
        assertEquals(9, request?.seq)
    }

    @Test
    fun aWrongTokenIsRefused() {
        assertNull(Protocol.parse("other-token dump", token))
    }

    @Test
    fun aPrefixOfTheTokenIsRefused() {
        assertNull(Protocol.parse("secret-token-123 dump", token))
    }

    @Test
    fun aLineWithoutATokenIsRefused() {
        assertNull(Protocol.parse("dump", token))
        assertNull(Protocol.parse("", token))
        assertNull(Protocol.parse("   ", token))
    }

    @Test
    fun aTokenWithoutACommandIsRefused() {
        assertNull(Protocol.parse(token, token))
        assertNull(Protocol.parse("$token   ", token))
    }

    @Test
    fun extraWhitespaceDoesNotShiftTheArguments() {
        val request = Protocol.parse("  $token   dump   250   7  ", token)
        assertEquals("dump", request?.cmd)
        assertEquals(250, request?.budget)
        assertEquals(7, request?.seq)
    }

    @Test
    fun aNonsenseBudgetFallsBackToTheDefault() {
        assertEquals(Protocol.DEFAULT_BUDGET, Protocol.parse("$token dump abc", token)?.budget)
        assertEquals(Protocol.DEFAULT_BUDGET, Protocol.parse("$token dump -5", token)?.budget)
    }

    @Test
    fun unknownCommandsArePassedThroughToBeAnswered() {
        // Parsing does not police the command: the service answers ERR for what it does not know.
        assertEquals("screenshot", Protocol.parse("$token screenshot", token)?.cmd)
    }

    @Test
    fun theFrameDeclaresItsLength() {
        val body = "{\"nodes\":1}".toByteArray(Charsets.UTF_8)
        val framed = Protocol.frame(body)
        assertEquals("OK ${body.size}\n{\"nodes\":1}", String(framed, Charsets.UTF_8))
        assertEquals(framed.size, "OK ${body.size}\n".length + body.size)
    }

    @Test
    fun anEmptyBodyFramesAsZero() {
        assertEquals("OK 0\n", String(Protocol.frame(ByteArray(0)), Charsets.UTF_8))
    }

    @Test
    fun aBodyWithNewlinesKeepsItsLength() {
        val body = "line1\nline2".toByteArray(Charsets.UTF_8)
        val framed = Protocol.frame(body)
        assertEquals("OK 11\nline1\nline2", String(framed, Charsets.UTF_8))
    }
}
