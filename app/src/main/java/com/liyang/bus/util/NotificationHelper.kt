package com.liyang.bus.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.liyang.bus.R
import com.liyang.bus.service.FloatingWindowService

object NotificationHelper {

    private const val CHANNEL_ID = "bus_arrival"
    private const val CHANNEL_NAME = "公交到站提醒"
    private const val NOTIFICATION_ID = 1001

    /** Call once in Application.onCreate() to create the notification channel. */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "公交车到站距离提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Check if the app has overlay permission.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Show an arrival-reminder with floating window (if permission granted) and notification.
     *
     * @param lineName      bus line name, e.g. "101路"
     * @param stationsAway  number of stations remaining
     * @param distance      distance string, e.g. "1.2km"
     */
    fun showArrivalNotification(
        context: Context,
        lineName: String,
        stationsAway: Int,
        distance: String
    ) {
        // On Android 13+ the runtime permission must be granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val message = "即将到站"
        val stationInfo = "还剩 ${stationsAway} 站（约 $distance）"

        // Try to show floating window if permission granted
        if (hasOverlayPermission(context)) {
            try {
                FloatingWindowService.showFloatingWindow(
                    context,
                    lineName,
                    message,
                    stationInfo
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Also show notification as fallback
        val text = "$lineName $stationInfo"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚌 公交到站提醒")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
