package com.silverbullet.checker.utils

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object FileUtils {

    fun readFileFromUri(context: Context, uri: Uri): List<String> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun cacheUri(context: Context, uri: Uri, name: String): String {
        val target = File(context.cacheDir, name)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return ""
            target.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    fun saveResultsToFile(context: Context, results: List<com.silverbullet.checker.models.Account>, fileName: String) {
        try {
            val hits = results.filter { it.status == com.silverbullet.checker.models.AccountStatus.HIT }
            val file = File(context.getExternalFilesDir(null), fileName)
            file.writeText(hits.joinToString("\n") { "${it.email}:${it.password} | ${it.details}" })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
