package io.github.zh_tx3.a11ybridge

/**
 * The loopback wire format: one line per request, one framed reply per request.
 *
 * ```
 * <token> dump [budgetMs] [reuse|-] [seq]   →  OK <bytes>\n<body>
 * <token> ping                              →  OK <bytes>\n<body>
 * ```
 *
 * The reply body is JSON for a read, and plain text on failure — a client that read the frame
 * length can always print it. A wrong token gets no reply at all: the connection is dropped, so
 * an app on the phone that probes the port learns nothing it did not already know.
 */
internal object Protocol {
    const val DEFAULT_BUDGET = 400

    class Request(val cmd: String, val budget: Int, val reuse: Boolean, val seq: Int)

    /** null means "wrong token or unusable line": the caller drops the connection silently. */
    fun parse(line: String, token: String): Request? {
        val trimmed = line.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return null
        if (trimmed.substring(0, space) != token) return null
        val parts = trimmed.substring(space + 1).trim().split(' ')
        val cmd = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Request(
            cmd = cmd,
            // A budget outside this range would either truncate everything or hang a read.
            budget = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..10_000 } ?: DEFAULT_BUDGET,
            reuse = parts.getOrNull(2) == "reuse",
            seq = parts.getOrNull(3)?.toIntOrNull() ?: 0,
        )
    }

    /** `OK <size>\n` then the body: the length is what makes the reply safe to read from nc. */
    fun frame(body: ByteArray): ByteArray {
        val head = "OK ${body.size}\n".toByteArray(Charsets.UTF_8)
        val out = ByteArray(head.size + body.size)
        head.copyInto(out)
        body.copyInto(out, head.size)
        return out
    }
}
