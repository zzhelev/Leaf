// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

private const val TAG = "AskpassServer"

const val ASKPASS_SOCKET_VARIABLE = "LEAF_ASKPASS_SOCKET"
const val ASKPASS_TOKEN_VARIABLE = "LEAF_ASKPASS_TOKEN"

/** A request is three NUL-terminated fields: the token, the kind and the payload. */
private const val REQUEST_FIELDS = 3
private const val MAX_REQUEST_SIZE = 1024 * 1024
private const val TOKEN_BYTES = 32

/** macOS refuses Unix socket paths longer than 104 bytes. */
private const val MAX_SOCKET_PATH_LENGTH = 100

private const val ANSWERED: Byte = '1'.code.toByte()
private const val REFUSED: Byte = '0'.code.toByte()

/** What `leaf-askpass` was run for. */
sealed interface AskpassRequest {
    /** A prompt from git or ssh, which expects an answer. */
    data class Prompt(val text: String) : AskpassRequest

    /** A yes or no question from ssh (`SSH_ASKPASS_PROMPT=confirm`). */
    data class Confirm(val text: String) : AskpassRequest

    /** A credential helper [operation] (`get`, `store`, `erase`), with the credential that git sent as [input]. */
    data class Credential(val operation: String, val input: String) : AskpassRequest
}

/**
 * Answers `leaf-askpass` while [block] runs a git command, then stops. [block] gets the variables that point the
 * helper to this server, which only the git command and the programs it starts receive.
 *
 * The server listens on a Unix socket in a new folder that only the user can open, or on a loopback port on Windows,
 * and answers only requests that carry the random token, so that no other program can read what the user types.
 *
 * [answer] returns the answer, or null when the user refused. It runs once per request, while the helper waits.
 */
suspend fun <T> withAskpassServer(
    answer: suspend (AskpassRequest) -> String?,
    block: suspend (environment: Map<String, String>) -> T,
): T = coroutineScope {
    val token = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }.toHex()
    val listener = AskpassListener.open()

    val serving = launch(Dispatchers.IO) { listener.serve(token, answer) }

    try {
        block(mapOf(ASKPASS_SOCKET_VARIABLE to listener.address, ASKPASS_TOKEN_VARIABLE to token))
    } finally {
        serving.cancel()
        listener.close()
    }
}

private class AskpassListener(
    private val channel: ServerSocketChannel,
    val address: String,
    private val socketDirectory: File?,
) {
    suspend fun serve(token: String, answer: suspend (AskpassRequest) -> String?) = coroutineScope {
        while (isActive) {
            val connection = try {
                runInterruptible { channel.accept() }
            } catch (e: IOException) {
                // Closed when the command is done
                return@coroutineScope
            }

            launch { connection.use { handle(it, token, answer) } }
        }
    }

    private suspend fun CoroutineScope.handle(
        connection: SocketChannel,
        token: String,
        answer: suspend (AskpassRequest) -> String?,
    ) {
        val fields = try {
            runInterruptible { connection.readFields() }
        } catch (e: IOException) {
            printError(TAG, "Could not read the helper's request", e)
            return
        } ?: return

        val (requestToken, kind, payload) = fields

        if (!MessageDigest.isEqual(requestToken.toByteArray(), token.toByteArray())) {
            printError(TAG, "Refused a request without this command's token")
            return
        }

        val request = when {
            kind == "prompt" -> AskpassRequest.Prompt(payload)
            kind == "confirm" -> AskpassRequest.Confirm(payload)
            kind.startsWith("credential-") -> AskpassRequest.Credential(kind.removePrefix("credential-"), payload)
            else -> {
                printError(TAG, "Unknown request kind '$kind'")
                return
            }
        }

        val reply = answer(request)

        if (!isActive) {
            return
        }

        val bytes = if (reply == null) {
            byteArrayOf(REFUSED)
        } else {
            byteArrayOf(ANSWERED) + reply.toByteArray()
        }

        try {
            runInterruptible {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    connection.write(buffer)
                }
            }
        } catch (e: IOException) {
            // The helper is gone: git or ssh stopped waiting for it
            printError(TAG, "Could not answer the helper", e)
        }
    }

    fun close() {
        channel.close()
        socketDirectory?.deleteRecursively()
    }

    companion object {
        fun open(): AskpassListener {
            if (currentOs == OS.WINDOWS) {
                // Rust's standard library has no Unix sockets on Windows, so the helper connects to a loopback port
                val channel = ServerSocketChannel.open()
                channel.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                val port = (channel.localAddress as InetSocketAddress).port

                return AskpassListener(channel, port.toString(), socketDirectory = null)
            }

            // Created readable by the user only, which keeps other users from connecting to the socket
            val directory = createSocketDirectory()
            val socket = directory.resolve("s")

            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

            try {
                channel.bind(UnixDomainSocketAddress.of(socket))
            } catch (e: IOException) {
                channel.close()
                directory.toFile().deleteRecursively()
                throw e
            }

            return AskpassListener(channel, socket.toString(), directory.toFile())
        }

        private fun createSocketDirectory(): Path {
            val directory = Files.createTempDirectory("leaf-askpass")

            if (directory.resolve("s").toString().length <= MAX_SOCKET_PATH_LENGTH) {
                return directory
            }

            // TMPDIR can be long enough to make the socket path too long
            Files.delete(directory)
            return Files.createTempDirectory(Path.of("/tmp"), "leaf-askpass")
        }
    }
}

/** Reads the request's fields, or returns null if the connection ends before it's complete. */
private fun SocketChannel.readFields(): List<String>? {
    val fields = mutableListOf<String>()
    val field = java.io.ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(4096)
    var size = 0

    while (fields.size < REQUEST_FIELDS) {
        buffer.clear()
        val count = read(buffer)

        if (count < 0) {
            return null
        }

        size += count
        if (size > MAX_REQUEST_SIZE) {
            throw IOException("The request is longer than $MAX_REQUEST_SIZE bytes")
        }

        buffer.flip()
        while (buffer.hasRemaining() && fields.size < REQUEST_FIELDS) {
            val byte = buffer.get()

            if (byte == 0.toByte()) {
                fields.add(field.toString(Charsets.UTF_8))
                field.reset()
            } else {
                field.write(byte.toInt())
            }
        }
    }

    return fields
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
