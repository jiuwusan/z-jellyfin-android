package org.jellyfin.mobile.authorization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class WebSession(val userId: String, val token: String)

internal class HomeWebSession private constructor(private val data: JsonObject) {
    private val servers = (data["Servers"] as? JsonArray).orEmpty()

    fun find(address: String, id: String): WebSession? {
        val record = servers.filterIsInstance<JsonObject>()
            .firstOrNull { it.string("Id") == id && it.uses(address) } ?: return null
        val user = record.string("UserId") ?: return null
        val token = record.string("AccessToken") ?: return null
        return WebSession(user, token)
    }

    fun replace(address: String, id: String, name: String, session: WebSession): String {
        val remaining = servers.filterNot { element ->
            element is JsonObject && (element.string("Id") == id || element.uses(address))
        }
        val selected = buildJsonObject {
            put("Id", id)
            put("Name", name)
            put("ManualAddress", address)
            put("LastConnectionMode", 2)
            put("manualAddressOnly", true)
            put("DateLastAccessed", System.currentTimeMillis())
            put("UserId", session.userId)
            put("AccessToken", session.token)
        }
        return JsonObject(data + ("Servers" to JsonArray(listOf(selected) + remaining))).toString()
    }

    private fun JsonObject.uses(address: String): Boolean =
        string("ManualAddress")?.let(HomeAuthorizationProfile::normalizeServer) == address

    companion object {
        fun parse(value: String?): HomeWebSession =
            HomeWebSession(runCatching { Json.parseToJsonElement(value.orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
    }
}

internal fun JsonElement?.objectValue(): JsonObject? = this as? JsonObject

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
