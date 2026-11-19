package org.jellyfin.mobile.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HomeHttpProxyTest {
    @Test
    fun `forwards range and injects credentials only on the upstream connection`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            val received = CompletableFuture<List<String>>()
            thread(isDaemon = true) {
                upstream.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    received.complete(generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList())
                    socket.getOutputStream().write(
                        "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 1-3/4\r\nContent-Length: 3\r\n\r\nbcd".toByteArray(),
                    )
                }
            }
            HomeHttpProxy("192.168.50.182:8096", "local-key").use { proxy ->
                proxy.profile = HomeProxyProfile("127.0.0.1", upstream.localPort, "reader", "local-test")
                val response = request(
                    proxy.port,
                    "GET http://192.168.50.182:8096/jellyfin/video HTTP/1.1\r\n" +
                        "Host: 192.168.50.182:8096\r\nRange: bytes=1-3\r\nProxy-Authorization: Basic injected\r\n" +
                        "User-Agent: Testbrowser\r\nCookie: jellyfin_home_proxy_client=local-key; other=value\r\n\r\n",
                )
                assertTrue(response.startsWith("HTTP/1.1 206"))
                assertTrue(response.contains("Content-Range: bytes 1-3/4"))
                assertTrue(response.endsWith("bcd"))
                val headers = received.get(3, TimeUnit.SECONDS)
                assertEquals("GET http://192.168.50.182:8096/jellyfin/video HTTP/1.1", headers.first())
                assertTrue("Range: bytes=1-3" in headers)
                assertTrue("User-Agent: Testbrowser" in headers)
                assertEquals(listOf("Cookie: other=value"), headers.filter { it.startsWith("Cookie:") })
                assertEquals(listOf("Proxy-Authorization: Basic cmVhZGVyOmxvY2FsLXRlc3Q="), headers.filter { it.startsWith("Proxy-Authorization:") })
            }
        }
    }

    @Test
    fun `rejects another origin and refuses requests without setup`() {
        HomeHttpProxy("192.168.50.182:8096", "local-key").use { proxy ->
            assertTrue(request(proxy.port, "GET http://192.168.50.182:8096/jellyfin/ HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
            proxy.profile = HomeProxyProfile("127.0.0.1", 1, "reader", "local-test")
            assertTrue(request(proxy.port, "CONNECT 192.168.50.182:22 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(
                request(proxy.port, "GET http://192.168.50.182:8096@other.invalid/x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"),
            )
            assertTrue(
                request(proxy.port, "GET http://192.168.50.182:8096/x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"),
            )
            assertTrue(
                request(
                    proxy.port,
                    "GET http://192.168.50.182:8096/x HTTP/1.1\r\nX-Jellyfin-Home-Client: local-key\r\n" +
                        "Content-Length: 1\r\nContent-Length: 2\r\n\r\n",
                ).startsWith("HTTP/1.1 400"),
            )
        }
    }

    @Test
    fun `CONNECT stays on the allowed authority and carries duplex WebSocket bytes`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            val received = CompletableFuture<List<String>>()
            thread(isDaemon = true) {
                upstream.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    received.complete(generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList())
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                    writer.flush()
                    val handshake = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    if (handshake.firstOrNull() != "GET /jellyfin/socket HTTP/1.1") return@use
                    writer.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
                    writer.flush()
                    writer.write("Echo: ${reader.readLine()}\n")
                    writer.flush()
                }
            }
            HomeHttpProxy("192.168.50.182:8096", "local-key").use { proxy ->
                proxy.profile = HomeProxyProfile("127.0.0.1", upstream.localPort, "reader", "local-test")
                Socket("127.0.0.1", proxy.port).use { client ->
                    client.soTimeout = 3000
                    val out = client.getOutputStream()
                    val input = client.getInputStream().bufferedReader()
                    out.write("CONNECT 192.168.50.182:8096 HTTP/1.1\r\n\r\n".toByteArray())
                    assertEquals("HTTP/1.1 200 Connection Established", input.readLine())
                    assertEquals("", input.readLine())
                    out.write(
                        ("GET /jellyfin/socket HTTP/1.1\r\nHost: 192.168.50.182:8096\r\n" +
                            "X-Jellyfin-Home-Client: local-key\r\nUpgrade: websocket\r\n\r\n").toByteArray(),
                    )
                    assertTrue(input.readLine().startsWith("HTTP/1.1 101"))
                    while (input.readLine().isNotEmpty()) Unit
                    out.write("upgrade-data\n".toByteArray())
                    assertEquals("Echo: upgrade-data", input.readLine())
                }
                val headers = received.get(3, TimeUnit.SECONDS)
                assertEquals("CONNECT 192.168.50.182:8096 HTTP/1.1", headers.first())
                assertTrue(headers.any { it.startsWith("Proxy-Authorization: Basic ") })
            }
        }
    }

    @Test
    fun `sends login request body and completes chunked response without waiting for upstream close`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            val received = CompletableFuture<String>()
            val sent = CompletableFuture<Unit>()
            thread(isDaemon = true) {
                upstream.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val body = CharArray(2)
                    reader.read(body, 0, body.size)
                    received.complete(String(body))
                    socket.getOutputStream().write(
                        ("HTTP/1.1 103 Early Hints\r\n\r\n" +
                            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n").toByteArray(),
                    )
                    sent.get(5, TimeUnit.SECONDS)
                }
            }
            HomeHttpProxy("192.168.50.182:8096", "local-key").use { proxy ->
                proxy.profile = HomeProxyProfile("127.0.0.1", upstream.localPort, "reader", "local-test")
                try {
                    val result = request(
                        proxy.port,
                        "POST http://192.168.50.182:8096/jellyfin/Users/AuthenticateByName HTTP/1.1\r\n" +
                            "X-Jellyfin-Home-Client: local-key\r\nContent-Length: 2\r\n\r\n{}",
                    )
                    assertTrue(result.startsWith("HTTP/1.1 200 OK"))
                    assertTrue(result.endsWith("3\r\nabc\r\n0\r\n\r\n"))
                    assertEquals("{}", received.get(3, TimeUnit.SECONDS))
                } finally {
                    sent.complete(Unit)
                }
            }
        }
    }

    private fun request(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write(request.toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
}
