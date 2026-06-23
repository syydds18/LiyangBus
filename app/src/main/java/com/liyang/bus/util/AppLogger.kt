package com.liyang.bus.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val logs = mutableListOf<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "bus_log.txt")
        log("Logger", "Log file: ${logFile?.absolutePath}")
    }

    fun log(tag: String, msg: String) {
        val line = "${sdf.format(Date())} [$tag] $msg"
        synchronized(logs) {
            logs.add(line)
            if (logs.size > 500) logs.removeAt(0)
        }
        Log.d(tag, msg)
        // Write to file (append)
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    fun getAll(): String = synchronized(logs) { logs.joinToString("\n") }

    fun getLogFile(): File? = logFile

    fun clear() {
        synchronized(logs) { logs.clear() }
        try { logFile?.writeText("") } catch (_: Exception) {}
    }

    fun copyToClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logs", getAll()))
    }
}
