package io.github.zh_tx3.a11ybridge

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An accessibility service that reads the node tree on demand and hands it out as JSON — over a
 * loopback socket for speed, or over a DUMP-protected broadcast as a fallback with nothing to set
 * up.
 *
 * Nothing is read until a request arrives, nothing is written to the screen, no activity is
 * started: the service exists to answer reads.
 */
class BridgeService : AccessibilityService() {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "a11y-bridge") }
    private val dir = File(filesDir, "a11y-bridge")
    private var bridge: Bridge? = null
    private var receiver: BroadcastReceiver? = null

    /** Accessibility events seen, so a poll over a still screen can be answered from the last read. */
    @Volatile private var events = 0L
    @Volatile private var lastBytes: ByteArray? = null
    @Volatile private var lastEvents = -1L
    @Volatile private var lastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        dir.mkdirs()
        bridge = Bridge({ request ->
            // Captures are serialized: a socket client and a broadcast never read at once.
            executor.submit(Callable { handle(request) }).get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        })
        register()
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        events++
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        bridge?.stop()
        bridge = null
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        executor.shutdownNow()
        Log.i(TAG, "gone")
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION)
            // The action the dsh-Go .a11y fork listens on: clients written against it keep working.
            addAction(LEGACY_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                val seq = intent.getIntExtra("seq", 0)
                executor.execute { run(cmd, intent, seq) }
            }
        }
        // android.permission.DUMP is held by adb shell and root, and by nothing else.
        registerReceiver(r, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun run(cmd: String, intent: Intent, seq: Int) {
        val start = SystemClock.uptimeMillis()
        val out = try {
            when (cmd) {
                "dump", "tree" -> dump(intent, seq)
                "listen" -> listen(intent)
                "unlisten" -> { bridge?.stop(); "stopped" }
                "ping" -> "pong"
                else -> "unknown cmd $cmd"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cmd $cmd failed", e)
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
        val text = "seq=$seq cmd=$cmd ms=${SystemClock.uptimeMillis() - start}\n$out\n"
        runCatching {
            val tmp = File(dir, "last.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "last.txt"))
        }
        text.chunked(3_000).forEach { Log.i(TAG, it) }
    }

    private fun handle(request: Protocol.Request): ByteArray = when (request.cmd) {
        "dump", "tree" -> captureJson(request.seq, request.budget, request.reuse)
        "ping" -> "pong".toByteArray(Charsets.UTF_8)
        else -> "ERR unknown cmd ${request.cmd}".toByteArray(Charsets.UTF_8)
    }

    private fun dump(intent: Intent, seq: Int): String {
        val budget = intent.getIntExtra("budget", Protocol.DEFAULT_BUDGET)
        val reuse = intent.getBooleanExtra("reuse", false)
        val bytes = captureJson(seq, budget, reuse)
        val name = (intent.getStringExtra("name") ?: "tree").replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val written = sinks().mapNotNull { sink -> File(sink, "$name.json").takeIf { writeAtomic(it, bytes) } }
        return buildString {
            append("bytes=${bytes.size}")
            append("\nfiles:\n")
            if (written.isEmpty()) append("- (none written)\n")
            else written.forEach { append("- ").append(it.absolutePath).append('\n') }
            append("----\n")
            append(String(bytes, Charsets.UTF_8).take(240))
        }
    }

    /**
     * `reuse`: answer from the last read when the accessibility event count has not moved. Off
     * unless asked for — no events is not proof that the pixels are the same (a ticking clock in
     * an unimportant view changes nothing that would raise one).
     */
    private fun captureJson(seq: Int, budget: Int, reuse: Boolean): ByteArray {
        val count = events
        val cached = lastBytes
        if (reuse && cached != null && count == lastEvents && SystemClock.uptimeMillis() - lastAt < REUSE_MS) {
            return cached
        }
        val start = SystemClock.uptimeMillis()
        val snapshot = Tree.read(windowSources(), screenBox(), packageName, budget.toLong())
        val bytes = TreeJson.stringify(snapshot, seq, SystemClock.uptimeMillis() - start).toByteArray(Charsets.UTF_8)
        lastBytes = bytes
        lastEvents = count
        lastAt = SystemClock.uptimeMillis()
        return bytes
    }

    private fun windowSources(): List<WindowSource> =
        try {
            windows.orEmpty().map { A11yWindow(it, this) }
        } catch (_: Throwable) {
            emptyList()
        }

    private fun listen(intent: Intent): String {
        val token = intent.getStringExtra("token")
        if (token == null || token.length < 8) return "ERR token must be at least 8 characters"
        val port = intent.getIntExtra("port", PORT)
        val bound = bridge?.start(token, port).orEmpty()
        if (bound.isEmpty()) return "ERR nothing bound on port $port (127.0.0.1, ::1)"
        return "listening on 127.0.0.1:${bound.first()} (and ::1), closes itself after 10 idle minutes"
    }

    /** Where a broadcast dump goes: the app's own files plus the external dir adb shell can read. */
    private fun sinks(): List<File> {
        val out = ArrayList<File>(2)
        out += dir
        getExternalFilesDir(null)?.let { out += File(it, "a11y-bridge") }
        return out
    }

    /** Write-then-rename, so a reader polling for the file never sees half of one. */
    private fun writeAtomic(file: File, bytes: ByteArray): Boolean =
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "write ${file.absolutePath}", e)
            false
        }

    private val displays by lazy { getSystemService(DisplayManager::class.java) }

    @Suppress("DEPRECATION")
    private fun screenBox(): Box {
        val point = Point()
        displays?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point)
        return Box(0, 0, point.x.coerceAtLeast(1), point.y.coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "A11yBridge"

        /** The DUMP-protected fallback channel. */
        const val ACTION = "io.github.zh_tx3.a11ybridge.PROBE"

        /** The dsh-Go .a11y fork's action, still answered so existing clients keep working. */
        const val LEGACY_ACTION = "io.github.ccch1mneyyy.dsh_app.A11Y_PROBE"

        /** Where the socket listens, for `adb forward tcp:PORT tcp:PORT`. */
        const val PORT = 47913

        private const val REUSE_MS = 2_000L
        private const val READ_TIMEOUT_MS = 20_000L
    }
}
