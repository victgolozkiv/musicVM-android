package com.musicplayer.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

class CrashHandler private constructor(context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "CrashHandler"

        @JvmStatic
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e(TAG, "Uncaught exception detected: ", e)
        try {
            val crashFile = File(context.getExternalFilesDir(null), "crash_log.txt")
            val fw = FileWriter(crashFile, true)
            val pw = PrintWriter(fw)
            pw.println("Crash Date: ${Date()}")
            pw.println("Thread: ${t.name}")
            e.printStackTrace(pw)
            pw.println("-------------------------------------------------------------------")
            pw.close()
            fw.close()
            Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write crash log", ex)
        }

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e)
        } else {
            System.exit(1)
        }
    }
}
