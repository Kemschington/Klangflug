package de.klangflug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pendingSystem = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun begin(system: Boolean) {
        pendingSystem = system
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else proceed()
    }

    private fun proceed() {
        if (pendingSystem) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 2)
        } else {
            startForegroundService(
                Intent(this, CaptureService::class.java).putExtra(CaptureService.EXTRA_SYSTEM, false)
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) proceed()
        else AudioBridge.error = "Mikrofon-Berechtigung fehlt."
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_SYSTEM, true)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
            )
        } else AudioBridge.error = "Die Aufnahme wurde nicht erlaubt."
    }

    inner class Bridge {
        @JavascriptInterface fun startSystem() { runOnUiThread { begin(true) } }
        @JavascriptInterface fun startMic() { runOnUiThread { begin(false) } }
        @JavascriptInterface fun frame(): String {
            val b = AudioBridge.bytes
            return String(CharArray(b.size) { ((b[it].toInt() and 0xFF) + 1).toChar() })
        }
        @JavascriptInterface fun error(): String {
            val e = AudioBridge.error
            AudioBridge.error = ""
            return e
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, CaptureService::class.java))
        super.onDestroy()
    }
}
