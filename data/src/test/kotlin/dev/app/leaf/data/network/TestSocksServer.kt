// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A SOCKS5 proxy on 127.0.0.1 for tests, without authentication. It connects to the address that [routes] gives for
 * the `host:port` that the client asks for, and notes each in [connections], so that a test can use names that
 * nothing resolves: a client that sent the name, not an address, left the lookup to the proxy.
 */
class TestSocksServer(private val routes: Map<String, InetSocketAddress>) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    val connections = CopyOnWriteArrayList<String>()

    val port: Int get() = serverSocket.localPort

    init {
        thread(isDaemon = true, name = "test-socks-$port") {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += client
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()

        // Greeting: version 5, then the methods the client offers. No authentication (0).
        check(input.readUnsignedByte() == 5)
        input.readNBytes(input.readUnsignedByte())
        output.write(byteArrayOf(5, 0))

        // CONNECT: version, command 1, reserved, then the address
        check(input.readUnsignedByte() == 5)
        check(input.readUnsignedByte() == 1)
        input.readUnsignedByte()

        val host = when (val type = input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(input.readNBytes(4)).hostAddress
            3 -> String(input.readNBytes(input.readUnsignedByte()), Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(input.readNBytes(16)).hostAddress
            else -> error("Address type $type")
        }
        val port = input.readUnsignedShort()
        val target = "$host:$port"
        connections += target

        val address = routes[target]

        if (address == null) {
            // Host unreachable
            output.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
            return
        }

        Socket(address.address, address.port).use { server ->
            sockets += server
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()

            val upstream = thread(isDaemon = true) {
                copy(input, server.getOutputStream())
                runCatching { server.shutdownOutput() }
            }
            copy(server.getInputStream(), output)
            upstream.join(1000)
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        try {
            input.copyTo(output)
            output.flush()
        } catch (e: IOException) {
            // One side closed
        }
    }

    override fun close() {
        serverSocket.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
