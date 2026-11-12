// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.imageio.ImageIO

/**
 * Avatars download with Coil's OkHttp fetcher and the OkHttp that Leaf ships, which Ktor's OkHttp engine, used for
 * requests through a proxy, raised from 4 to 5.
 */
class AvatarDownloadTest {
    @Test
    fun `an avatar downloads over HTTP`(): Unit = runBlocking {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/avatar.png") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()

        try {
            val context = PlatformContext.INSTANCE
            val request = ImageRequest.Builder(context)
                .data("http://127.0.0.1:${server.address.port}/avatar.png")
                .build()

            val result = ImageLoader.Builder(context).build().execute(request)

            assertTrue(result is SuccessResult, (result as? ErrorResult)?.throwable?.toString())
            assertEquals(3, (result as SuccessResult).image.width)
        } finally {
            server.stop(0)
        }
    }
}
