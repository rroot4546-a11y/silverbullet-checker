package com.silverbullet.checker.models

data class Account(
    val email: String,
    val password: String,
    var status: AccountStatus = AccountStatus.UNCHECKED,
    var details: String = "",
    var responseCode: Int = 0
)

enum class AccountStatus(val label: String, val color: Int) {
    UNCHECKED("Unchecked", 0xFF757575.toInt()),
    HIT("HIT", 0xFF4CAF50.toInt()),
    FAIL("FAIL", 0xFFF44336.toInt()),
    RETRY("RETRY", 0xFFFF9800.toInt()),
    BAN("BAN", 0xFF9C27B0.toInt()),
    CAPTCHA("CAPTCHA", 0xFF03A9F4.toInt()),
    CHECKING("Checking", 0xFF2196F3.toInt()),
    ERROR("ERROR", 0xFFE91E63.toInt())
}

data class Combo(
    val email: String,
    val password: String
) {
    companion object {
        fun parseLine(line: String): Combo? {
            val parts = line.trim().split(":", "|", ";", ",", " ")
            return when {
                parts.size >= 2 -> Combo(parts[0].trim(), parts[1].trim())
                parts.size == 1 && parts[0].contains("@") -> Combo(parts[0].trim(), "")
                else -> null
            }
        }
    }
}

data class Proxy(
    val ip: String,
    val port: Int,
    val type: ProxyType = ProxyType.HTTP,
    val username: String = "",
    val password: String = "",
    var isActive: Boolean = true,
    var failCount: Int = 0
) {
    fun toHostString(): String = "$ip:$port"

    fun toAuthString(): String = if (username.isNotEmpty()) {
        "$username:$password@$ip:$port"
    } else {
        "$ip:$port"
    }

    companion object {
        fun parseLine(line: String): Proxy? {
            val clean = line.trim()
            val parts = clean.split("@")
            return try {
                if (parts.size == 2) {
                    val auth = parts[0].split(":")
                    val host = parts[1].split(":")
                    Proxy(
                        ip = host[0],
                        port = host[1].toInt(),
                        username = auth[0],
                        password = auth[1]
                    )
                } else {
                    val host = clean.split(":")
                    Proxy(ip = host[0], port = host[1].toInt())
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

enum class ProxyType(val scheme: String) {
    HTTP("http"),
    SOCKS4("socks4"),
    SOCKS5("socks5")
}

data class CheckConfig(
    var threads: Int = 10,
    var timeout: Int = 10,
    var useProxy: Boolean = false,
    var proxyType: ProxyType = ProxyType.HTTP,
    var module: CheckModule = CheckModule.GENERIC,
    var customUrl: String = "",
    var customMethod: String = "POST",
    var customBodyTemplate: String = "{\"email\":\"{email}\",\"password\":\"{password}\"}",
    var customContentType: String = "application/json",
    var customSuccessMarker: String = "",
    var customFailMarker: String = "",
    var captureHits: Boolean = true,
    var captureFails: Boolean = false,
    var retries: Int = 1
) : java.io.Serializable

enum class CheckModule(val displayName: String, val endpoint: String) {
    GENERIC("Generic Login", "https://example.com/login"),
    SPOTIFY("Spotify", "https://spclient.wg.spotify.com"),
    NETFLIX("Netflix", "https://www.netflix.com/login"),
    DISCORD("Discord", "https://discord.com/api/v9/auth/login"),
    CRUNCHYROLL("Crunchyroll", "https://www.crunchyroll.com"),
    ROBLOX("Roblox", "https://auth.roblox.com/v2/login"),
    AMAZON("Amazon", "https://www.amazon.com"),
    NIKE("Nike", "https://api.nike.com"),
    ADOBE("Adobe", "https://ims-na1.adobelogin.com"),
    CUSTOM("Custom", "")
}

data class CheckStats(
    var total: Int = 0,
    var checked: Int = 0,
    var hits: Int = 0,
    var fails: Int = 0,
    var retries: Int = 0,
    var banned: Int = 0,
    var captcha: Int = 0,
    var errors: Int = 0,
    var cpm: Float = 0f,
    var startTime: Long = 0L
) {
    fun reset() {
        total = 0; checked = 0; hits = 0; fails = 0
        retries = 0; banned = 0; captcha = 0; errors = 0
        cpm = 0f; startTime = System.currentTimeMillis()
    }

    fun computeCpm(): Float {
        val elapsed = (System.currentTimeMillis() - startTime) / 60000f
        return if (elapsed > 0) checked / elapsed else 0f
    }
}
