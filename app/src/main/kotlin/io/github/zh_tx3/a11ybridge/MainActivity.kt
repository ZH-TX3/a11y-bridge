package io.github.zh_tx3.a11ybridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * There is nothing to configure here: this screen exists to say whether the service is on and to
 * open the page where it is switched on. Every read comes from outside (adb).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            textSize = 15f
            text = buildString {
                append(if (enabled()) "服务已开启" else "服务未开启")
                append("\n\n")
                append("adb forward tcp:").append(BridgeService.PORT).append(" tcp:").append(BridgeService.PORT)
                append("\n")
                append("adb shell am broadcast -a ").append(BridgeService.ACTION)
                append(" --es cmd dump")
            }
        }
        val open = Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(48, 120, 48, 48)
                addView(status)
                addView(open)
            },
        )
    }

    private fun enabled(): Boolean {
        val list = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Throwable) {
            null
        } ?: return false
        val me = ComponentName(this, BridgeService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }
}
