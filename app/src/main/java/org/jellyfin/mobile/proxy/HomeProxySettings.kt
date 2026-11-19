package org.jellyfin.mobile.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.webkit.CookieManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.jellyfin.mobile.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.Executor
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class HomeProxySettings(context: Context) {
    val enabled: Boolean get() = BuildConfig.HOME_PROXY_TRIAL
    private val store = AtomicFile(File(context.noBackupFilesDir, "home-proxy-profile.enc"))
    private val legacyStore = AtomicFile(File(context.noBackupFilesDir, "home-proxy.enc"))
    private val clientKey = UUID.randomUUID().toString()
    @Volatile var profile = if (enabled) loadProfile() else null
        private set
    private val ready = MutableStateFlow(profile != null)
    val readiness = ready.asStateFlow()
    private val relay by lazy {
        HomeHttpProxy(AUTHORITY, clientKey).also { it.profile = profile }
    }
    private val loopback by lazy { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port)) }

    fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!enabled) return builder
        val system = ProxySelector.getDefault()
        return builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.scheme == "http" && request.url.host == "192.168.50.182" && request.url.port == 8096 &&
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
                request.url.scheme == "http" && request.url.host == "192.168.50.182" && request.url.port == 8096
            ) {
                chain.proceed(request.newBuilder().header(HomeHttpProxy.CLIENT_HEADER, clientKey).build())
            } else {
                chain.proceed(request)
            }
        }.proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.scheme == "http" && uri.rawAuthority == AUTHORITY) {
                listOf(loopback)
            } else {
                system?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                if (uri.rawAuthority != AUTHORITY) system?.connectFailed(uri, address, error)
            }
        })
    }

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
            CookieManager.getInstance().setCookie(SERVER, "${HomeHttpProxy.CLIENT_COOKIE}=$clientKey; Path=/; HttpOnly") { accepted ->
                onReady(accepted)
            }
        }
        return true
    }

    private fun loadProfile(): HomeProxyProfile? = runCatching {
        try {
            HomeProxyProfile.decode(decrypt(store.readFully()))
        } catch (_: FileNotFoundException) {
            HomeProxyProfile.fromLegacyPassword(decrypt(legacyStore.readFully()))
        }
    }.getOrNull()

    private fun decrypt(bytes: ByteArray): String {
        val key = loadKey() ?: throw GeneralSecurityException("Proxy profile key is unavailable")
        require(bytes.size > IV_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        }.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun loadKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    fun saveProfile(value: HomeProxyProfile, changePassword: String): Boolean {
        if (profile != null && changePassword != CHANGE_PASSWORD) return false
        val activeRelay = relay
        val key = loadKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        val encryptor = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        if (encryptor.iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected credential cipher IV")
        val payload = encryptor.iv + encryptor.doFinal(value.encode().toByteArray(Charsets.UTF_8))
        val out = store.startWrite()
        try {
            out.write(payload)
            store.finishWrite(out)
        } catch (e: IOException) {
            store.failWrite(out)
            throw e
        }
        profile = value
        activeRelay.profile = value
        legacyStore.delete()
        ready.value = true
        return true
    }

    companion object {
        const val SERVER = "http://192.168.50.182:8096/jellyfin"
        private const val AUTHORITY = "192.168.50.182:8096"
        private const val KEY_ALIAS = "jellyfin-home-proxy-credential"
        private const val CHANGE_PASSWORD = "jellyfin"
        private const val IV_BYTES = 12
    }
}
