package chromahub.rhythm.app.infrastructure.audio.usb

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class UsbVolumeOSD(context: Context) {
    private val appContext = context.applicationContext
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private val text: TextView = TextView(appContext).apply {
        setBackgroundColor(Color.argb(180, 10, 10, 10))
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(28, 16, 28, 16)
    }

    private var isVisible = false
    private val dismissRunnable = Runnable { dismiss() }

    fun show(volumePercent: Int, routingMode: RoutingMode) {
        text.text = "USB ${routingMode.name.replace('_', ' ')} - $volumePercent%"
        if (!isVisible) {
            wm.addView(text, createLayoutParams())
            isVisible = true
        }
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, 2000)
    }

    fun dismiss() {
        if (!isVisible) return
        try {
            wm.removeView(text)
        } catch (_: Throwable) {
        }
        isVisible = false
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = android.graphics.PixelFormat.TRANSLUCENT
        }
    }
}
