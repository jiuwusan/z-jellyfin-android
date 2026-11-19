package org.jellyfin.mobile.proxy

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A process-local relay for one permitted HTTP origin. Raw responses preserve Range and HLS. */
internal class HomeHttpProxy(
    private val originAuthority: String,
    private val clientKey: String,
) : Closeable {
    @Volatile var profile: HomeProxyProfile? = null
    private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0), 32) }
    val port: Int = listener.localPort
    private val workers = ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "jellyfin-proxy-request").apply { isDaemon = true }
    }
    private val sockets = mutableSetOf<Socket>()

    init {
        thread(name = "jellyfin-proxy-listener", isDaemon = true) {
            try {
                while (!listener.isClosed) {
                    val client = listener.accept()
                    if (!track(client)) continue
                    try {
                        workers.execute {
                            try {
                                client.use(::handle)
                            } catch (_: IOException) {
                                // Cancellation and network changes often close a socket in flight.
                            } finally {
                                synchronized(sockets) { sockets.remove(client) }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        synchronized(sockets) { sockets.remove(client) }
                        client.close()
                    }
                }
            } catch (_: IOException) {
                close()
            }
        }
    }

    private fun track(socket: Socket): Boolean = synchronized(sockets) {
        if (listener.isClosed) {
            socket.close()
            false
        } else {
            sockets.add(socket)
            true
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val initial = readRequest(input) ?: return output.error(400, "Invalid HTTP request")
        val connect = initial.method == "CONNECT"
        val upstream = profile ?: return output.error(503, "Home proxy setup required")
        val request = if (connect) {
            if (initial.target != originAuthority) return output.error(403, "Destination is outside the home media server")
            if (initial.headers.keys.any { it == "transfer-encoding" || it == "content-length" }) {
                return output.error(400, "Invalid CONNECT framing")
            }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            readRequest(input) ?: return output.error(400, "Invalid tunneled HTTP request")
        } else {
            initial
        }
        val target = runCatching { URI(request.target) }.getOrNull()
        val permitted = if (connect) {
            request.method == "GET" && target != null && target.rawPath.startsWith("/") && !target.isAbsolute &&
                target.rawAuthority == null && target.rawFragment == null &&
                request.headers["host"]?.singleOrNull()?.second == originAuthority
        } else {
            target != null && target.scheme in setOf("http", "ws") &&
                target.rawAuthority == originAuthority && target.rawFragment == null
        }
        if (!permitted) return output.error(403, "Destination is outside the home media server")
        val cookies = request.headers["cookie"].orEmpty().flatMap { it.second.split(';').map(String::trim) }
        if ("$CLIENT_COOKIE=$clientKey" !in cookies && request.headers[CLIENT_HEADER]?.singleOrNull()?.second != clientKey) {
            return output.error(403, "Unrecognized local client")
        }
        val authorization = Base64.getEncoder()
            .encodeToString("${upstream.username}:${upstream.password}".toByteArray(Charsets.UTF_8))
        val upgrade = request.headers["upgrade"]?.singleOrNull()?.second.equals("websocket", ignoreCase = true)
        if ((connect || request.headers.containsKey("upgrade")) && !upgrade) return output.error(400, "Unsupported upgrade")
        if (request.headers.containsKey("transfer-encoding") || request.headers.containsKey("expect")) {
            return output.error(400, "Unsupported request framing")
        }
        val lengths = request.headers["content-length"].orEmpty()
        val bodyLength = lengths.singleOrNull()?.second?.toLongOrNull() ?: if (lengths.isEmpty()) 0 else -1
        if (bodyLength !in 0..16_777_216 || (upgrade && bodyLength != 0L)) {
            return output.error(400, "Invalid request length")
        }

        val remote = Socket(Proxy.NO_PROXY)
        if (!track(remote)) return
        try {
            try {
                remote.connect(InetSocketAddress(upstream.socketHost, upstream.port), 8000)
            } catch (_: IOException) {
                return output.error(502, "Home proxy is unreachable")
            }
            remote.soTimeout = 60_000
            val remoteOutput = remote.getOutputStream()
            val remoteInput = BufferedInputStream(remote.getInputStream())
            if (upgrade) {
                remoteOutput.write(
                    ("CONNECT $originAuthority HTTP/1.1\r\nHost: $originAuthority\r\n" +
                        "Proxy-Authorization: Basic $authorization\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                val tunnel = readHeaders(remoteInput)
                if (tunnel?.first?.substringAfter(' ')?.substringBefore(' ') != "200") {
                    return output.error(502, "Home proxy tunnel failed")
                }
            }
            val upstreamTarget = if (upgrade && !connect) {
                val uri = checkNotNull(target)
                uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            } else {
                request.target
            }
            val fields = buildString {
                append("${request.method} $upstreamTarget HTTP/1.1\r\n")
                append("Host: $originAuthority\r\n")
                if (!upgrade) append("Proxy-Authorization: Basic $authorization\r\n")
                val nominated = request.headers["connection"].orEmpty().flatMap { header ->
                    header.second.split(',').map { it.trim().lowercase(Locale.ROOT) }
                }
                for ((key, values) in request.headers) {
                    if (key !in hopHeaders && key !in nominated) {
                        for ((name, value) in values) append("$name: $value\r\n")
                    }
                }
                val forwardedCookies = cookies.filter { it.isNotEmpty() && it.substringBefore('=') != CLIENT_COOKIE }
                if (forwardedCookies.isNotEmpty()) append("Cookie: ${forwardedCookies.joinToString("; ")}\r\n")
                if (bodyLength > 0 || lengths.isNotEmpty()) append("Content-Length: $bodyLength\r\n")
                if (upgrade) append("Connection: Upgrade\r\nUpgrade: websocket\r\n")
                else append("Connection: close\r\n")
                append("\r\n")
            }
            remoteOutput.write(fields.toByteArray(Charsets.ISO_8859_1))
            copyExact(input, remoteOutput, bodyLength)
            remoteOutput.flush()

            val (status, response) = readFinalResponse(remoteInput)
                ?: return output.error(502, "Invalid response from home proxy")
            if (status == 407) return output.error(502, "Home proxy authentication failed")
            val duplex = upgrade && status == 101
            val responseFields = if (duplex) response.second else response.second.filterNot {
                it.substringBefore(':').equals("Connection", ignoreCase = true)
            } + "Connection: close"
            val responseHead = (listOf(response.first) + responseFields).joinToString("\r\n", postfix = "\r\n\r\n")
            output.write(responseHead.toByteArray(Charsets.ISO_8859_1))
            if (duplex) {
                client.soTimeout = 0
                remote.soTimeout = 0
                thread(name = "jellyfin-proxy-websocket", isDaemon = true) {
                    try {
                        input.copyTo(remoteOutput)
                        remote.shutdownOutput()
                    } catch (_: IOException) {
                        runCatching { remote.close() }
                    }
                }
            }
            if (duplex) {
                remoteInput.copyTo(output, 64 * 1024)
            } else if (request.method != "HEAD" && status !in setOf(204, 304)) {
                val length = responseFields.singleOrNull { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                val transferEncoding = responseFields.firstOrNull { it.substringBefore(':').equals("Transfer-Encoding", ignoreCase = true) }
                    ?.substringAfter(':')?.substringAfterLast(',')?.trim()
                if (transferEncoding.equals("chunked", ignoreCase = true)) {
                    copyChunked(remoteInput, output)
                } else if (length != null && transferEncoding == null) {
                    copyExact(remoteInput, output, length)
                } else {
                    remoteInput.copyTo(output, 64 * 1024)
                }
            }
        } finally {
            remote.close()
            synchronized(sockets) { sockets.remove(remote) }
        }
    }

    override fun close() {
        listener.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        workers.shutdownNow()
    }

    companion object {
        const val CLIENT_HEADER = "x-jellyfin-home-client"
        const val CLIENT_COOKIE = "jellyfin_home_proxy_client"
        private val token = Regex("[A-Za-z0-9!#$%&'*+.^_\u0060|~-]+")
        private val methods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT")
        private val hopHeaders = setOf(
            "host", "connection", "proxy-connection", "proxy-authorization", "keep-alive", "upgrade",
            "transfer-encoding", "trailer", "te", "content-length", "cookie", CLIENT_HEADER,
        )
    }

    private data class Request(val method: String, val target: String, val headers: Map<String, List<Pair<String, String>>>)

    private fun readRequest(input: InputStream): Request? {
        val (start, lines) = readHeaders(input) ?: return null
        val parts = start.split(' ')
        if (parts.size != 3 || parts[0] !in methods || parts[2] !in setOf("HTTP/1.1", "HTTP/1.0")) return null
        val headers = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (line in lines) {
            val name = line.substringBefore(':')
            if (':' !in line || !token.matches(name)) return null
            val value = line.substringAfter(':').trim()
            headers.getOrPut(name.lowercase(Locale.ROOT)) { mutableListOf() }.add(name to value)
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readHeaders(input: InputStream): Pair<String, List<String>>? {
        val buffer = StringBuilder()
        val lines = mutableListOf<String>()
        var total = 0
        while (total++ < 64 * 1024) {
            when (val b = input.read()) {
                -1 -> return null
                13 -> {
                    if (input.read() != 10) return null
                    if (buffer.isEmpty()) return lines.firstOrNull()?.let { it to lines.drop(1) }
                    lines.add(buffer.toString())
                    buffer.setLength(0)
                }
                9, in 32..126, in 128..255 -> buffer.append(b.toChar())
                else -> return null
            }
        }
        return null
    }

    private fun readFinalResponse(input: InputStream): Pair<Int, Pair<String, List<String>>>? {
        repeat(8) {
            val response = readHeaders(input) ?: return null
            val status = response.first.substringAfter(' ', "").substringBefore(' ').toIntOrNull() ?: return null
            if (status == 101 || status in 200..599) return status to response
            if (status !in 100..199) return null
        }
        return null
    }

    private fun copyChunked(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var trailerLines = 0
        while (true) {
            val line = readChunkLine(input)
            val size = line.substringBefore(';').toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("Invalid chunk size")
            output.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
            if (size == 0L) {
                do {
                    val trailer = readChunkLine(input)
                    output.write("$trailer\r\n".toByteArray(Charsets.ISO_8859_1))
                    if (trailer.isEmpty()) return
                } while (++trailerLines < 64)
                throw IOException("Too many chunk trailers")
            }
            copyExact(input, output, size, buffer)
            if (input.read() != 13 || input.read() != 10) throw IOException("Invalid chunk terminator")
            output.write(13)
            output.write(10)
        }
    }

    private fun readChunkLine(input: InputStream): String {
        val line = StringBuilder()
        repeat(8192) {
            when (val byte = input.read()) {
                13 -> if (input.read() == 10) return line.toString() else throw IOException("Invalid chunk line")
                9, in 32..126, in 128..255 -> line.append(byte.toChar())
                else -> throw IOException("Invalid chunk line")
            }
        }
        throw IOException("Chunk line too long")
    }

    private fun copyExact(input: InputStream, output: OutputStream, length: Long, buffer: ByteArray = ByteArray(64 * 1024)) {
        if (length == 0L) return
        var left = length
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (count < 0) throw EOFException()
            output.write(buffer, 0, count)
            left -= count
        }
    }

    private fun OutputStream.error(status: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        write("HTTP/1.1 $status $message\r\nConnection: close\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        write(body)
    }
}
