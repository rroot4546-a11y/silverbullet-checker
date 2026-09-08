package com.silverbullet.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.silverbullet.checker.utils.LogStore

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)
        val btnRefresh = findViewById<Button>(R.id.btnLogRefresh)
        val btnCopy = findViewById<Button>(R.id.btnLogCopy)
        val btnClear = findViewById<Button>(R.id.btnLogClear)
        val btnBack = findViewById<Button>(R.id.btnLogBack)

        LogStore.init(this)
        refresh()

        btnRefresh.setOnClickListener { refresh() }
        btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SilverBullet log", tvLog.text.toString()))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        btnClear.setOnClickListener {
            LogStore.clear()
            tvLog.text = "(log cleared)"
        }
        btnBack.setOnClickListener { finish() }
    }

    private fun refresh() {
        tvLog.text = LogStore.readLog()
    }
}