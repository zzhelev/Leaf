// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A request that reached [TestProxy]: its method, its target as sent, and its headers, by lower-case name. */
data class ProxiedRequest(val method: String, val target: String, val headers: Map<String, String>) {
    val proxyAuthorization: String? get() = headers["proxy-authorization"]

    override fun toString() = "$method $target"
}

/**
 * An HTTP proxy on 127.0.0.1 for tests. It forwards a request in absolute form (`GET http://host/path`) and tunnels a
 * `CONNECT host:port`, to the address that [routes] gives for `host:port`, so that a test can use host names that
 * nothing resolves: a request that reaches the server went through the proxy. With [credentials] (`user:password`), a
 * request without them gets 407, as a proxy that asks for a password answers.
 */
class TestProxy(
    private val routes: Map<String, InetSocketAddress> = emptyMap(),
    private val credentials: String? = null,
) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    val requests = CopyOnWriteArrayList<ProxiedRequest>()

    val port: Int get() = serverSocket.localPort

    val url: String get() = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "test-proxy-$port") {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += client
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            while (true) {
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val (method, target) = lines.first().split(" ").let { it[0] to it[1] }
                val headers = lines.drop(1).filter { it.isNotEmpty() }.map {
                    it.substringBefore(":").trim() to it.substringAfter(":").trim()
                }
                val request = ProxiedRequest(method, target, headers.associate { it.first.lowercase() to it.second })
                val proxyAuthorization = request.proxyAuthorization
                requests += request

                if (credentials != null && proxyAuthorization != basic(credentials)) {
                    // A body the client already sent would be read as the next request
                    readBody(input, headers)
                    output.write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n" +
                            "Content-Length: 0\r\n\r\n").toByteArray()
                    )
                    output.flush()
                    continue
                }

                if (method == "CONNECT") {
                    tunnel(target, input, output)
                    return
                }

                if (!forward(target, method, headers, readBody(input, headers), output)) {
                    return
                }
            }
        }
    }

    private fun tunnel(target: String, input: InputStream, output: OutputStream) {
        val address = routes[target]

        if (address == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        Socket(address.address, address.port).use { server ->
            sockets += server
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()

            val upstream = thread(isDaemon = true) {
                copy(input, server.getOutputStream())
                runCatching { server.shutdownOutput() }
            }
            copy(server.getInputStream(), output)
            upstream.join(1000)
        }
    }

    /** Sends the request to its server and its answer back; false when the client's connection can't go on. */
    private fun forward(
        target: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        output: OutputStream,
    ): Boolean {
        val uri = URI(target)
        val port = if (uri.port == -1) 80 else uri.port
        val address = routes["${uri.host}:$port"]

        if (address == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return true
        }

        val pathAndQuery = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val forwardedHeaders = headers.filterNot { (name, _) ->
            name.equals("Proxy-Authorization", true) ||
                name.equals("Proxy-Connection", true) ||
                name.equals("Connection", true) ||
                name.equals("Content-Length", true) ||
                name.equals("Transfer-Encoding", true)
        }

        // The body as read, with its length
        val request = buildString {
            append("$method $pathAndQuery HTTP/1.1\r\n")
            forwardedHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            if (body.isNotEmpty() || method == "POST" || method == "PUT") {
                append("Content-Length: ${body.size}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }

        Socket(address.address, address.port).use { server ->
            sockets += server
            server.getOutputStream().apply {
                write(request.toByteArray())
                write(body)
                flush()
            }
            // The server closes the connection after its answer, which says where it ends
            server.getInputStream().copyTo(output)
            output.flush()
        }

        return true
    }

    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0

        while (true) {
            val byte = input.read()

            if (byte == -1) {
                return null
            }

            head.write(byte)
            matched = when {
                byte == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }

            if (matched == 4) {
                return head.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            }
        }
    }

    private fun readBody(input: InputStream, headers: List<Pair<String, String>>): ByteArray {
        val length = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toInt()

        if (length != null) {
            return input.readNBytes(length)
        }

        val chunked = headers.any { it.first.equals("Transfer-Encoding", true) && it.second.equals("chunked", true) }

        if (!chunked) {
            return ByteArray(0)
        }

        // Sent on as one body with its length
        val body = ByteArrayOutputStream()

        while (true) {
            val size = readLine(input).substringBefore(";").trim().toInt(16)

            if (size == 0) {
                readLine(input)
                break
            }

            body.write(input.readNBytes(size))
            readLine(input)
        }

        return body.toByteArray()
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()

        while (true) {
            val byte = input.read()

            if (byte == -1 || byte == '\n'.code) {
                return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
            }

            line.write(byte)
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(16 * 1024)

            while (true) {
                val read = input.read(buffer)

                if (read == -1) {
                    break
                }

                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: IOException) {
            // One side closed
        }
    }

    override fun close() {
        serverSocket.close()
        sockets.forEach { runCatching { it.close() } }
    }

    companion object {
        fun basic(credentials: String): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
    }
}
