package org.jellyfin.mobile.app

import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.jellyfin.mobile.proxy.HomeProxySettings
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val apiModule = module {
    // Jellyfin API builder and API client instance
    single {
        val homeProxy = get<HomeProxySettings>()
        val homeClient = if (homeProxy.enabled) OkHttpFactory(get<OkHttpClient>()) else null
        createJellyfin {
            context = androidContext()
            clientInfo = ClientInfo(name = Constants.APP_INFO_NAME, version = Constants.APP_INFO_VERSION)
            if (homeClient != null) {
                apiClientFactory = homeClient
                socketConnectionFactory = object : SocketConnectionFactory {
                    override fun create(clientOptions: HttpClientOptions, scope: CoroutineScope): SocketConnection =
                        homeClient.create(clientOptions.copy(followRedirects = false), scope)
                }
            }
        }
    }
    single { get<Jellyfin>().createApi() }
}
