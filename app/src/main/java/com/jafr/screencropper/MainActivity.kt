package com.jafr.screencropper

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val REQ_OVERLAY = 100
    private val REQ_CAPTURE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val info = TextView(this).apply {
            text = "ScreenCropper\n\nاضغط تشغيل، وامنح الصلاحيات، ثم افتح اللعبة."
            textSize = 18f
        }
        val start = Button(this).apply { text = "تشغيل" }
        val stop = Button(this).apply { text = "إيقاف" }

        layout.addView(info)
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)

        start.setOnClickListener { askOverlay() }
        stop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }
    }

    private fun askOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQ_OVERLAY
            )
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) askCapture()
        } else if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            moveTaskToBack(true)
        }
    }
}
