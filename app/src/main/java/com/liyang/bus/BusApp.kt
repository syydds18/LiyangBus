package com.liyang.bus

import android.app.Application
import com.liyang.bus.util.AppLogger
import com.liyang.bus.util.NotificationHelper
import java.io.PrintWriter
import java.io.StringWriter

class BusApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            AppLogger.log("CRASH", sw.toString())
            defaultHandler?.uncaughtException(thread, throwable)
        }

        AppLogger.init(this)
        FavoritesManager.init(this)
        NotificationHelper.init(this)
    }
}
