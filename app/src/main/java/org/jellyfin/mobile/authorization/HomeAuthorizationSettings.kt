package org.jellyfin.mobile.authorization

import android.content.Context
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.app.EncryptedProfileStore
import org.jellyfin.mobile.app.HOME_SETTINGS_CONFIRMATION_PASSWORD

internal class HomeAuthorizationSettings(context: Context) {
    private val store = EncryptedProfileStore(context, "home-authorization.enc", "jellyfin-home-authorization")
    private val preferences = context.getSharedPreferences("home-authorization", Context.MODE_PRIVATE)

    @Volatile
    var profile = if (BuildConfig.HOME_PROXY_TRIAL) runCatching { Json.decodeFromString<HomeAuthorizationProfile>(store.read()) }.getOrNull() else null
        private set

    @Synchronized
    fun save(value: HomeAuthorizationProfile, confirmation: String): Boolean {
        if (profile != null && confirmation != HOME_SETTINGS_CONFIRMATION_PASSWORD) return false
        store.write(Json.encodeToString(value))
        preferences.edit().remove(REJECTED).apply()
        profile = value
        return true
    }

    @Synchronized
    fun clear(confirmation: String): Boolean {
        if (confirmation != HOME_SETTINGS_CONFIRMATION_PASSWORD) return false
        store.delete()
        preferences.edit().remove(REJECTED).apply()
        profile = null
        return true
    }

    @Synchronized
    fun canAuthenticate(value: HomeAuthorizationProfile): Boolean = profile === value && !preferences.getBoolean(REJECTED, false)

    @Synchronized
    fun markRejected(value: HomeAuthorizationProfile) {
        if (profile === value) preferences.edit().putBoolean(REJECTED, true).apply()
    }

    private companion object {
        const val REJECTED = "credentialsRejected"
    }
}
