package org.jellyfin.mobile.authorization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeWebSessionTest {
    private val address = "http://media.example.test:8096/jellyfin"
    private val saved = """{"Servers":[{"Id":"previous","ManualAddress":"$address/","UserId":"old-user","AccessToken":"old-token"},{"Id":"other","ManualAddress":"http://other.example.test"}]}"""

    @Test
    fun `a changed server id never reuses the old access token`() {
        val credentials = HomeWebSession.parse(saved)
        assertNull(credentials.find(address, "replacement"))
        assertEquals("old-token", credentials.find(address, "previous")?.token)
    }

    @Test
    fun `replacing a server removes the old identity and preserves unrelated servers`() {
        val credentials = HomeWebSession.parse(saved).replace(address, "replacement", "Media", WebSession("new-user", "new-token"))
        val servers = Json.parseToJsonElement(credentials).jsonObject.getValue("Servers").jsonArray
        assertEquals(2, servers.size)
        assertEquals("new-token", HomeWebSession.parse(credentials).find(address, "replacement")?.token)
        assertNull(HomeWebSession.parse(credentials).find(address, "previous"))
    }

    @Test
    fun `authorization is scoped to an HTTP Jellyfin base URL`() {
        assertEquals(address, HomeAuthorizationProfile.normalizeServer("$address/"))
        assertNull(HomeAuthorizationProfile.normalizeServer("https://media.example.test"))
        assertNull(HomeAuthorizationProfile.normalizeServer("http://user:pass@media.example.test"))
        assertNull(HomeAuthorizationProfile.normalizeServer("$address?target=other"))
    }
}
