// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections

/**
 * A Git LFS server for tests, on 127.0.0.1: the batch API and the basic transfer, with the objects in [storage].
 * With [credentials] set, the batch API wants them (Basic), and the actions it answers carry them, as a real server's
 * carry a token.
 */
class FakeLfsServer(private val storage: File) : AutoCloseable {
    @Volatile
    var credentials: Pair<String, String>? = null

    /** When set, a download sends only that many bytes of the object, then drops the connection. */
    @Volatile
    var dropDownloadsAfter: Int? = null

    /** `METHOD path user-agent` for each request. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}/lfs"

    init {
        storage.mkdirs()
        server.createContext("/lfs") { exchange -> exchange.use { handle(it) } }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/lfs")
        requests += "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("User-Agent").orEmpty()}"

        val expected = credentials?.let { (user, password) ->
            "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        }

        if (expected != null && exchange.requestHeaders.getFirst("Authorization") != expected) {
            exchange.responseHeaders.add("LFS-Authenticate", "Basic realm=\"lfs\"")
            exchange.sendResponseHeaders(401, -1)
            return
        }

        when {
            exchange.requestMethod == "POST" && path == "/objects/batch" -> batch(exchange, expected)
            exchange.requestMethod == "GET" && path.startsWith("/objects/") -> download(exchange, path)
            exchange.requestMethod == "PUT" && path.startsWith("/objects/") -> upload(exchange, path)
            // Such as git-lfs's lock verification, which it then skips
            else -> exchange.sendResponseHeaders(404, -1)
        }
    }

    private fun batch(exchange: HttpExchange, authorization: String?) {
        val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        val operation = request.getValue("operation").jsonPrimitive.content

        val response = buildJsonObject {
            put("transfer", "basic")
            put("objects", buildJsonArray {
                for (item in request.getValue("objects").jsonArray) {
                    val oid = item.jsonObject.getValue("oid").jsonPrimitive.content
                    val size = item.jsonObject.getValue("size").jsonPrimitive.long
                    add(objectEntry(oid, size, operation, authorization))
                }
            })
        }.toString().toByteArray()

        exchange.responseHeaders.add("Content-Type", "application/vnd.git-lfs+json")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun objectEntry(oid: String, size: Long, operation: String, authorization: String?): JsonObject {
        val stored = File(storage, oid).isFile

        return buildJsonObject {
            put("oid", oid)
            put("size", size)
            put("authenticated", true)

            when {
                operation == "download" && !stored -> putJsonObject("error") {
                    put("code", 404)
                    put("message", "Object does not exist")
                }
                // An object the server has needs no upload
                operation == "upload" && stored -> {}
                else -> putJsonObject("actions") {
                    putJsonObject(operation) {
                        put("href", "$url/objects/$oid")
                        putJsonObject("header") {
                            authorization?.let { put("Authorization", it) }
                        }
                    }
                }
            }
        }
    }

    private fun download(exchange: HttpExchange, path: String) {
        val file = File(storage, path.substringAfterLast('/'))

        if (!file.isFile) {
            exchange.sendResponseHeaders(404, -1)
            return
        }

        val cut = dropDownloadsAfter

        exchange.sendResponseHeaders(200, file.length())

        if (cut == null) {
            exchange.responseBody.use { file.inputStream().use { input -> input.copyTo(it) } }
        } else {
            // Closing the exchange with fewer bytes than announced makes the server drop the connection
            exchange.responseBody.write(file.readBytes(), 0, cut)
            exchange.responseBody.flush()
        }
    }

    private fun upload(exchange: HttpExchange, path: String) {
        File(storage, path.substringAfterLast('/')).writeBytes(exchange.requestBody.readBytes())
        exchange.sendResponseHeaders(200, -1)
    }

    override fun close() = server.stop(0)
}
