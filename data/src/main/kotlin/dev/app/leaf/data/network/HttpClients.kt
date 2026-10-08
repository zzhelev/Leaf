// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.URIish
import java.net.URISyntaxException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The client for Leaf's own HTTP requests (Git LFS, the update check). It checks TLS certificates against the JVM's
 * trust store.
 */
fun createHttpClient(): HttpClient = HttpClient(CIO) {
    installLogging()
}

/**
 * A client that accepts any TLS certificate. Only for requests to URLs whose `http.sslVerify` is false
 * ([isSslVerify]), as git and git-lfs allow.
 */
fun createHttpClientWithoutTlsVerification(): HttpClient = HttpClient(CIO) {
    installLogging()

    engine {
        https {
            trustManager = TrustAllCertificates
        }
    }
}

/**
 * Whether git checks the TLS certificate of [url]: `http.<url>.sslVerify` for the best matching URL, then
 * `http.sslVerify`, true by default. git-lfs reads it the same way for each request's URL.
 */
fun Config.isSslVerify(url: String): Boolean {
    val uri = try {
        URIish(url)
    } catch (e: URISyntaxException) {
        return true
    }

    return HttpConfig(this, uri).isSslVerify
}

private fun HttpClientConfig<*>.installLogging() {
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.NONE
    }
}

private object TrustAllCertificates : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
