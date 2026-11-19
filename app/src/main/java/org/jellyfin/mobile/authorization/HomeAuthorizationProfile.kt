package org.jellyfin.mobile.authorization

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

@Serializable
internal class HomeAuthorizationProfile(val server: String, val username: String, val password: String) {
    init {
        require(normalizeServer(server) == server)
        require(username.isNotBlank() && password.isNotEmpty())
    }

    companion object {
        fun normalizeServer(input: String): String? {
            val value = input.trim()
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.rawAuthority == null || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null
            ) return null
            return value.toHttpUrlOrNull()?.toString()?.trimEnd('/')
        }
    }
}
