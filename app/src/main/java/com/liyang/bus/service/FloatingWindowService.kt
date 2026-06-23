package com.liyang.bus.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.liyang.bus.R

class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var vibrator: Vibrator? = null

    companion object {
        private const val EXTRA_LINE_NAME = "line_name"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_STATION_INFO = "station_info"

        fun showFloatingWindow(
            context: Context,
            lineName: String,
            message: String,
            stationInfo: String
        ) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                putExtra(EXTRA_LINE_NAME, lineName)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_STATION_INFO, stationInfo)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lineName = intent?.getStringExtra(EXTRA_LINE_NAME) ?: "公交"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "即将到站"
        val stationInfo = intent?.getStringExtra(EXTRA_STATION_INFO) ?: ""

        showFloatingWindow(lineName, message, stationInfo)
        vibrate()

        return START_NOT_STICKY
    }

    private fun showFloatingWindow(lineName: String, message: String, stationInfo: String) {
        if (floatingView != null) {
            removeFloatingWindow()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_alert, null)

        // Update content
        floatingView?.apply {
            findViewById<TextView>(R.id.tvLineName).text = lineName
            findViewById<TextView>(R.id.tvMessage).text = message
            findViewById<TextView>(R.id.tvStationInfo).text = stationInfo

            findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
                removeFloatingWindow()
                stopSelf()
            }

            findViewById<Button>(R.id.btnDismiss).setOnClickListener {
                removeFloatingWindow()
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun vibrate() {
        // Vibration pattern: wait 0ms, vibrate 500ms, wait 200ms, vibrate 500ms, wait 200ms, vibrate 500ms
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun removeFloatingWindow() {
        try {
            floatingView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        floatingView = null
    }

    override fun onDestroy() {
        removeFloatingWindow()
        super.onDestroy()
    }
}
