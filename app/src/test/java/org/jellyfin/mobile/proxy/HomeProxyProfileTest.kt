package org.jellyfin.mobile.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeProxyProfileTest {
    @Test
    fun `existing password keeps the original proxy defaults`() {
        val profile = HomeProxyProfile.fromLegacyPassword("old-secret")
        assertEquals("cloud.jiuwusan.cn", profile.host)
        assertEquals(37893, profile.port)
        assertEquals("Clash", profile.username)
        assertEquals("old-secret", profile.password)
    }

    @Test
    fun `profile round trip reloads all saved values`() {
        val profile = HomeProxyProfile("proxy.example.net", 8080, "reader", "new-secret")
        assertEquals(profile, HomeProxyProfile.decode(profile.encode()))
        assertTrue(!profile.toString().contains("new-secret"))
    }

    @Test
    fun `rejects schemes paths inline ports and invalid credentials`() {
        for (host in listOf("https://proxy.example.net", "proxy.example.net/path", "proxy.example.net:8080", "")) {
            assertThrows(IllegalArgumentException::class.java) { HomeProxyProfile(host, 8080, "reader", "secret") }
        }
        for (port in listOf(0, -1, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { HomeProxyProfile("proxy.example.net", port, "reader", "secret") }
        }
        assertThrows(IllegalArgumentException::class.java) { HomeProxyProfile("proxy.example.net", 8080, "user:name", "secret") }
    }
}
