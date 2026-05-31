package com.example.sayuri

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SayuriApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val trace = sw.toString()
                Log.e("SayuriApp", "Uncaught exception: $trace")

                try {
                    val file = File(filesDir, "sayuri_crash.txt")
                    file.appendText("=== Uncaught Exception (${System.currentTimeMillis()}) ===\n")
                    file.appendText(trace)
                    file.appendText("\n\n")

                    // Also write to external files dir so the file is accessible via adb/Device File Explorer
                    try {
                        val ext = getExternalFilesDir(null)
                        if (ext != null) {
                            val extFile = File(ext, "sayuri_crash.txt")
                            extFile.appendText("=== Uncaught Exception (${System.currentTimeMillis()}) ===\n")
                            extFile.appendText(trace)
                            extFile.appendText("\n\n")
                        }
                    } catch (e2: Exception) {
                        Log.e("SayuriApp", "Failed to write external crash file: ${e2.message}")
                    }
                } catch (e: Exception) {
                    Log.e("SayuriApp", "Failed to write crash file: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("SayuriApp", "Error in uncaught handler: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
