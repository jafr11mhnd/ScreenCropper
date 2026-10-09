package com.jafr.screencropper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null

    private var floatBtn: Button? = null
    private var selector: View? = null
    private var viewer: FrameLayout? = null
    private var viewerImage: ImageView? = null

    private var sw = 0
    private var sh = 0
    private var dpi = 0
    private var cropRect: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        sw = metrics.widthPixels
        sh = metrics.heightPixels
        dpi = metrics.densityDpi

        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            setupCapture()
            showFloatingButton()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val id = "cropper"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(id, "ScreenCropper", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, id)
            .setContentTitle("ScreenCropper")
            .setContentText("الخدمة تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupCapture() {
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vd = projection?.createVirtualDisplay(
            "cropper", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showFloatingButton() {
        val btn = Button(this).apply {
            text = "✂"
            textSize = 18f
            alpha = 0.7f
        }
        val lp = WindowManager.LayoutParams(
            150, 150, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 300
        }
        btn.setOnClickListener {
            if (viewer != null) closeViewer() else startSelecting()
        }
        wm.addView(btn, lp)
        floatBtn = btn
    }

    private fun startSelecting() {
        floatBtn?.visibility = View.GONE
        val view = object : View(this) {
            var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
            val paint = android.graphics.Paint().apply {
                color = Color.GREEN
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
            }
            override fun onDraw(c: android.graphics.Canvas) {
                c.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), paint)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ex = sx; ey = sy }
                    MotionEvent.ACTION_MOVE -> { ex = e.rawX; ey = e.rawY; invalidate() }
                    MotionEvent.ACTION_UP -> {
                        ex = e.rawX; ey = e.rawY
                        val r = Rect(
                            minOf(sx, ex).toInt().coerceAtLeast(0),
                            minOf(sy, ey).toInt().coerceAtLeast(0),
                            maxOf(sx, ex).toInt().coerceAtMost(sw),
                            maxOf(sy, ey).toInt().coerceAtMost(sh)
                        )
                        finishSelecting(r)
                    }
                }
                return true
            }
        }
        view.setBackgroundColor(Color.parseColor("#55000000"))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, lp)
        selector = view
    }

    private fun finishSelecting(r: Rect) {
        selector?.let { wm.removeView(it) }
        selector = null
        floatBtn?.visibility = View.VISIBLE
        if (r.width() < 100 || r.height() < 100) return
        cropRect = r
        showViewer()
    }

    private fun showViewer() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(frame, lp)
        viewer = frame
        viewerImage = img

        // الزر العائم يبقى فوق الشاشة الممددة
        floatBtn?.let { wm.removeView(it); wm.addView(it, it.layoutParams) }

        running = true
        handler.post(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updateFrame()
            handler.postDelayed(this, 50)
        }
    }

    private fun updateFrame() {
        val image = reader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * sw
            val bmp = Bitmap.createBitmap(
                sw + rowPadding / pixelStride, sh, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            val r = cropRect ?: return
            val cropped = Bitmap.createBitmap(
                bmp, r.left, r.top,
                r.width().coerceAtMost(bmp.width - r.left),
                r.height().coerceAtMost(bmp.height - r.top)
            )
            viewerImage?.setImageBitmap(cropped)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun closeViewer() {
        running = false
        handler.removeCallbacks(updateRunnable)
        viewer?.let { wm.removeView(it) }
        viewer = null
        viewerImage = null
    }

    override fun onDestroy() {
        closeViewer()
        selector?.let { wm.removeView(it) }
        floatBtn?.let { wm.removeView(it) }
        vd?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
