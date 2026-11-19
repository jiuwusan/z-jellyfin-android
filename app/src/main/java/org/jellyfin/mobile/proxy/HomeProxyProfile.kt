package org.jellyfin.mobile.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

@Serializable
internal data class HomeProxyProfile(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    init {
        require(normalizeHost(host) != null)
        require(port in 1..65535)
        require(isValidUsername(username))
        require(password.isNotBlank())
    }

    val socketHost: String get() = requireNotNull(normalizeHost(host))

    fun encode(): String = Json.encodeToString(this)

    override fun toString() = "HomeProxyProfile(host=$host, port=$port, username=$username, password=[redacted])"

    companion object {
        const val DEFAULT_HOST = "cloud.jiuwusan.cn"
        const val DEFAULT_PORT = 37893
        const val DEFAULT_USERNAME = "Clash"

        fun fromLegacyPassword(password: String) = HomeProxyProfile(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_USERNAME, password)

        fun decode(value: String): HomeProxyProfile = Json.decodeFromString<HomeProxyProfile>(value).copy()

        fun normalizeHost(value: String): String? = runCatching {
            HttpUrl.Builder().scheme("http").host(value).build().host
        }.getOrNull()

        fun isValidUsername(value: String): Boolean = value.isNotBlank() && ':' !in value && value.none(Char::isISOControl)
    }
}
