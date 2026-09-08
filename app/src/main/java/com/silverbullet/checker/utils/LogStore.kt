package com.silverbullet.checker.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {

    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "checker_log.txt")
        }
    }

    fun log(context: Context, line: String) {
        init(context)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "[$stamp] $line"
        synchronized(lock) {
            try {
                file?.appendText(entry + "\n")
            } catch (_: Exception) {}
        }
    }

    fun readLog(): String = synchronized(lock) {
        try {
            file?.readText()?.takeLast(60000) ?: "(no log file yet)"
        } catch (e: Exception) {
            "(failed to read log: ${e.message})"
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                file?.delete()
            } catch (_: Exception) {}
        }
    }
}