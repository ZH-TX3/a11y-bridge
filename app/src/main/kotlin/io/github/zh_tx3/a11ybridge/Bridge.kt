package io.github.zh_tx3.a11ybridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The loopback listener: reads come back over `adb forward` in a few milliseconds instead of
 * paying for an `am broadcast` process each time.
 *
 * Both loopback addresses are bound — `adb forward` arrives over IPv4, and a client inside the
 * phone's own Linux environment may well try `::1`. Never the wildcard: a screen reader must not
 * answer from the LAN.
 *
 * One thread per client, with an idle timeout, because a client that vanishes mid-line must not
 * pin the port (it did, once, and every later read queued behind it).
 */
internal class Bridge(
    /** Runs on the caller's executor, so captures stay serialized. */
    private val handler: (Protocol.Request) -> ByteArray,
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var token: String? = null
    private var servers: List<ServerSocket> = emptyList()

    /** Returns the ports actually bound, one per address that took. Port 0 picks an ephemeral one. */
    fun start(token: String, port: Int): List<Int> {
        stop()
        this.token = token
        val opened = ArrayList<ServerSocket>(2)
        val ports = ArrayList<Int>(2)
        for (host in LOOPBACKS) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(host), port))
                opened += socket
                ports += socket.localPort
            } catch (e: Throwable) {
                log("bind $host:$port failed: ${e.javaClass.simpleName}")
            }
        }
        servers = opened
        for (socket in opened) {
            Thread({ acceptLoop(socket) }, "bridge-accept").apply {
                isDaemon = true
                start()
            }
        }
        return ports
    }

    fun stop() {
        token = null
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({
                try {
                    serve(client)
                } catch (e: Throwable) {
                    log("client: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }, "bridge-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = IDLE_TIMEOUT_MS
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val output = client.getOutputStream()
        while (true) {
            val line = input.readLine() ?: return
            val expected = token ?: return
            val request = Protocol.parse(line, expected) ?: return
            val body = try {
                handler(request)
            } catch (e: Throwable) {
                "ERR ${e.javaClass.simpleName}: ${e.message}".toByteArray(Charsets.UTF_8)
            }
            output.write(Protocol.frame(body))
            output.flush()
        }
    }

    private companion object {
        val LOOPBACKS = listOf("127.0.0.1", "::1")
        const val IDLE_TIMEOUT_MS = 60_000
    }
}
