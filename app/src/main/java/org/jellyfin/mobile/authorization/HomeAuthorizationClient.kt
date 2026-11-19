package org.jellyfin.mobile.authorization

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.mobile.proxy.HomeProxySettings
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

internal class HomeAuthorizationClient(
    private val settings: HomeAuthorizationSettings,
    private val proxy: HomeProxySettings,
    private val httpClient: OkHttpClient,
    private val jellyfin: Jellyfin,
) {
    suspend fun restore(profile: HomeAuthorizationProfile, stored: HomeWebSession): Result = withContext(Dispatchers.IO) {
        if (settings.profile !== profile) return@withContext Result.Unchanged
        val client = proxy.authorizationClient(httpClient)
        try {
            val info = request(client, profile, "System/Info/Public").body ?: return@withContext Result.Unchanged
            val serverId = info.string("Id") ?: return@withContext Result.Unchanged
            if (info.string("StartupWizardCompleted") == "false") return@withContext Result.Unchanged

            val previous = stored.find(profile.server, serverId)
            if (previous != null) {
                val user = request(client, profile, "Users/Me", token = previous.token)
                if (user.body?.string("Name")?.equals(profile.username, ignoreCase = true) == true &&
                    user.body.string("Id")?.toUUIDOrNull() == previous.userId.toUUIDOrNull()
                ) return@withContext Result.Unchanged
                if (user.status != 200 && user.status != 401 && user.status != 403) return@withContext Result.Unchanged
            }

            ensureActive()
            if (!settings.canAuthenticate(profile)) return@withContext Result.Rejected
            val body = buildJsonObject {
                put("Username", profile.username)
                put("Pw", profile.password)
            }
            val login = request(client, profile, "Users/AuthenticateByName", body = body)
            if (login.status == 401 || login.status == 403) {
                settings.markRejected(profile)
                return@withContext Result.Rejected
            }
            val auth = login.body ?: return@withContext Result.Unchanged
            if (auth.string("ServerId") != serverId || settings.profile !== profile) return@withContext Result.Unchanged
            val user = auth["User"].objectValue()?.string("Id") ?: return@withContext Result.Unchanged
            if (user.toUUIDOrNull() == null) return@withContext Result.Unchanged
            val token = auth.string("AccessToken") ?: return@withContext Result.Unchanged
            val state = stored.replace(profile.server, serverId, info.string("ServerName").orEmpty(), WebSession(user, token))
            Result.Restored(state, WebSession(user, token))
        } catch (_: IOException) {
            Result.Unchanged
        }
    }

    private fun request(
        client: OkHttpClient,
        profile: HomeAuthorizationProfile,
        path: String,
        token: String? = null,
        body: JsonObject? = null,
    ): Response {
        val request = Request.Builder()
            .url("${profile.server}/$path")
            .header("Accept", "application/json")
            .header("Authorization", authorizationHeader(token))
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(request.build()).execute().use { response ->
            val payload = if (response.isSuccessful) response.body?.string() else null
            val data = payload?.let { runCatching { Json.parseToJsonElement(it).objectValue() }.getOrNull() }
            Response(response.code, data)
        }
    }

    private fun authorizationHeader(token: String?): String {
        val device = requireNotNull(jellyfin.options.deviceInfo)
        val client = requireNotNull(jellyfin.options.clientInfo)
        val fields = listOf("Client" to client.name, "Version" to client.version, "Device" to device.name, "DeviceId" to device.id) +
            listOfNotNull(token?.let { "Token" to it })
        return fields.joinToString(", ", prefix = "MediaBrowser ") { (key, value) ->
            "$key=\"${AuthorizationHeaderBuilder.encodeParameterValue(value)}\""
        }
    }

    private class Response(val status: Int, val body: JsonObject?)

    sealed interface Result {
        data object Unchanged : Result
        data object Rejected : Result
        class Restored(val credentials: String, val session: WebSession) : Result
    }
}
