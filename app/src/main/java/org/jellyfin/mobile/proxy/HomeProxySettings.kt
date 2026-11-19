package org.jellyfin.mobile.proxy

import android.content.Context
import android.webkit.CookieManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.jellyfin.mobile.app.EncryptedProfileStore
import org.jellyfin.mobile.app.HOME_SETTINGS_CONFIRMATION_PASSWORD
import org.jellyfin.mobile.authorization.HomeAuthorizationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.jellyfin.mobile.BuildConfig
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor
import java.util.UUID

internal class HomeProxySettings(context: Context, private val authorization: HomeAuthorizationSettings) {
    val enabled: Boolean get() = BuildConfig.HOME_PROXY_TRIAL
    val server: String get() = authorization.profile?.server ?: SERVER
    private val authority: String get() = URI(server).rawAuthority
    private val store = EncryptedProfileStore(context, "home-proxy-profile.enc", KEY_ALIAS)
    private val legacyStore = EncryptedProfileStore(context, "home-proxy.enc", KEY_ALIAS)
    private val clientKey = UUID.randomUUID().toString()
    @Volatile var profile = if (enabled) loadProfile() else null
        private set
    private val ready = MutableStateFlow(profile != null)
    val readiness = ready.asStateFlow()
    private val relay by lazy {
        HomeHttpProxy({ authority }, clientKey).also { it.profile = profile }
    }
    private val loopback by lazy { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port)) }

    fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!enabled) return builder
        val system = ProxySelector.getDefault()
        return builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.scheme == "http" && request.url.toUri().rawAuthority == authority &&
                chain.connection()?.route()?.proxy == loopback
            ) {
                chain.proceed(request.newBuilder().header(HomeHttpProxy.CLIENT_HEADER, clientKey).build())
            } else {
                chain.proceed(request)
            }
        }.addInterceptor { chain ->
            val request = chain.request()
            // OkHttp does not run network interceptors for WebSocket upgrades.
            if (request.header("Upgrade").equals("websocket", ignoreCase = true) &&
                request.url.scheme == "http" && request.url.toUri().rawAuthority == authority
            ) {
                chain.proceed(request.newBuilder().header(HomeHttpProxy.CLIENT_HEADER, clientKey).build())
            } else {
                chain.proceed(request)
            }
        }.proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.scheme == "http" && uri.rawAuthority == authority) {
                listOf(loopback)
            } else {
                system?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                if (uri.rawAuthority != authority) system?.connectFailed(uri, address, error)
            }
        })
    }

    fun authorizationClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .proxy(loopback)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun configureWebView(executor: Executor, onReady: (Boolean) -> Unit): Boolean {
        if (!enabled) {
            executor.execute { onReady(true) }
            return true
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        val rules = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:${relay.port}")
            .removeImplicitRules()
            .build()
        ProxyController.getInstance().setProxyOverride(rules, executor) {
            CookieManager.getInstance().setCookie(server, "${HomeHttpProxy.CLIENT_COOKIE}=$clientKey; Path=/; HttpOnly") { accepted ->
                onReady(accepted)
            }
        }
        return true
    }

    private fun loadProfile(): HomeProxyProfile? = runCatching {
        try {
            HomeProxyProfile.decode(store.read())
        } catch (_: FileNotFoundException) {
            HomeProxyProfile.fromLegacyPassword(legacyStore.read())
        }
    }.getOrNull()

    fun saveProfile(value: HomeProxyProfile, changePassword: String): Boolean {
        if (profile != null && changePassword != HOME_SETTINGS_CONFIRMATION_PASSWORD) return false
        val activeRelay = relay
        store.write(value.encode())
        profile = value
        activeRelay.profile = value
        legacyStore.delete()
        ready.value = true
        return true
    }

    companion object {
        const val SERVER = "http://192.168.50.182:8096/jellyfin"
        private const val KEY_ALIAS = "jellyfin-home-proxy-credential"
    }
}
