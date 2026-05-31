package com.example.server

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtility {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun logError(source: String, throwable: Throwable) {
        Log.e("LogUtility", "[$source] Unhandled/Caught Exception: ", throwable)
        try {
            val ctx = appContext ?: return
            val folder = ctx.getExternalFilesDir(null) ?: return
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val logFile = File(folder, "gateway.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val trace = Log.getStackTraceString(throwable)
            logFile.appendText("[$timestamp] [$source] Error occurred:\n$trace\n\n")
        } catch (e: Exception) {
            Log.e("LogUtility", "Failed to write error to gateway.log", e)
        }
    }

    @Synchronized
    fun logMessage(source: String, message: String) {
        Log.i("LogUtility", "[$source] $message")
        try {
            val ctx = appContext ?: return
            val folder = ctx.getExternalFilesDir(null) ?: return
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val logFile = File(folder, "gateway.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logFile.appendText("[$timestamp] [$source] INFO: $message\n")
        } catch (e: Exception) {
            Log.e("LogUtility", "Failed to write log message to gateway.log", e)
        }
    }
}
