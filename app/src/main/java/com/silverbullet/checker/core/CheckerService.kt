package com.silverbullet.checker.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.silverbullet.checker.MainActivity
import com.silverbullet.checker.R
import com.silverbullet.checker.models.*
import com.silverbullet.checker.utils.LogStore
import kotlinx.coroutines.*
import java.io.File

class CheckerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checker: AccountChecker? = null
    private var netflixWebView: WebView? = null
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "checker_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getSerializableExtra("config") as? CheckConfig ?: CheckConfig()
                val comboPath = intent.getStringExtra("combo_path") ?: ""
                val proxyPath = intent.getStringExtra("proxy_path") ?: ""

                startChecker(config, comboPath, proxyPath)
            }
            ACTION_STOP -> stopChecker()
        }
        return START_NOT_STICKY
    }

    private fun startChecker(config: CheckConfig, comboPath: String, proxyPath: String) {
        if (isRunning) return
        isRunning = true
        LogStore.log(this, "Checker started, module=${config.module}")

        startForeground(NOTIFICATION_ID, createNotification("Starting checker..."))

        scope.launch {
            val combos = loadCombos(comboPath)
            val proxies = loadProxies(proxyPath)

            checker = AccountChecker(config, buildNetflixWebView(config)).apply {
                loadProxies(proxies)
                onResult = { result ->
                    sendBroadcast(Intent("CHECKER_RESULT").apply {
                        putExtra("email", result.email)
                        putExtra("status", result.status.name)
                        putExtra("details", result.details)
                        setPackage(packageName)
                    })
                }
                onStatsUpdate = { stats ->
                    updateNotification(stats)
                }
                onLog = { line ->
                    LogStore.log(this@CheckerService, line)
                }
            }

            checker?.startChecking(combos, scope)

            withContext(Dispatchers.Main) {
                checker?.let {
                    while (isActive) {
                        delay(1000)
                        if (it.results.size >= combos.size) break
                    }
                }
                stopChecker()
            }
        }
    }

    private fun stopChecker() {
        isRunning = false
        checker?.stopChecking()
        scope.cancel()
        destroyNetflixWebView()
        stopForeground(true)
        stopSelf()
    }

    private fun buildNetflixWebView(config: CheckConfig): WebView? {
        if (config.module != CheckModule.NETFLIX || config.twoCaptchaKey.isNotBlank()) return null
        return try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    WebView(this@CheckerService).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    }.also { netflixWebView = it }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun destroyNetflixWebView() {
        val wv = netflixWebView ?: return
        netflixWebView = null
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    wv.stopLoading()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadCombos(path: String): List<Combo> {
        return try {
            File(path).readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { Combo.parseLine(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadProxies(path: String): List<Proxy> {
        if (path.isEmpty()) return emptyList()
        return try {
            File(path).readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { Proxy.parseLine(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Account Checker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Checker running notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SilverBullet")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(stats: CheckStats) {
        val text = "Checked: ${stats.checked}/${stats.total} | " +
                "Hits: ${stats.hits} | Fails: ${stats.fails} | " +
                "CPM: ${String.format("%.0f", stats.computeCpm())}"

        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        checker?.stopChecking()
        scope.cancel()
    }
}
