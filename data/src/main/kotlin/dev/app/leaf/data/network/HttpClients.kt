// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.network.ProxyChoice
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.logging.*
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.URIish
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URISyntaxException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "HttpClients"

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

/** How long a request through a proxy waits for the next byte, either way. */
private const val PROXIED_IO_TIMEOUT_SECONDS = 30L

/**
 * The client for Leaf's own HTTP requests (Git LFS, the update check) that go directly, without a proxy (for those,
 * [ProxiedHttpClients]). It checks TLS certificates against the JVM's trust store.
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
 * The clients for requests through a proxy (fork-only), one per proxy and `http.sslVerify`, kept for as long as Leaf
 * runs. Requests without one keep their own client.
 */
@Singleton
class ProxiedHttpClients @Inject constructor() {
    private val clients = ConcurrentHashMap<Pair<ProxyChoice, Boolean>, HttpClient>()

    /**
     * The client for requests through [proxy], or null for [ProxyChoice.Direct]. Throws [UnusableProxyException] for a
     * proxy that Leaf can't use, rather than going around it.
     */
    fun clientFor(proxy: ProxyChoice, sslVerify: Boolean): HttpClient? = when (proxy) {
        ProxyChoice.Direct -> null
        is ProxyChoice.Unusable -> throw UnusableProxyException(proxy)
        is ProxyChoice.Http, is ProxyChoice.Socks -> clients.computeIfAbsent(proxy to sslVerify) {
            createProxiedHttpClient(proxy, sslVerify)
        }
    }
}

/** A request whose proxy Leaf can't use. Its message names the proxy without its user name and password. */
class UnusableProxyException(val proxy: ProxyChoice.Unusable) :
    Exception("Can't use the proxy ${proxy.proxy} that ${proxy.source} names (${proxy.reason})")

/**
 * A client whose requests go through [proxy]. It runs on OkHttp, as Ktor's CIO engine sends a proxy's credentials to
 * the server too, inside the tunnel of an HTTPS request, and goes around a SOCKS proxy without a word. OkHttp sends
 * them to the proxy alone: in the `CONNECT` of HTTPS requests, and in the plain HTTP requests that the proxy forwards.
 */
private fun createProxiedHttpClient(proxy: ProxyChoice, sslVerify: Boolean): HttpClient {
    val (type, host, port) = when (proxy) {
        is ProxyChoice.Http -> Triple(Proxy.Type.HTTP, proxy.host, proxy.port)
        is ProxyChoice.Socks -> Triple(Proxy.Type.SOCKS, proxy.host, proxy.port)
        else -> error("Not a proxy: $proxy")
    }
    val credentials = (proxy as? ProxyChoice.Http)?.credentials
    val source = (proxy as? ProxyChoice.Http)?.source ?: (proxy as ProxyChoice.Socks).source

    printLog(
        TAG,
        "Requests go through the ${type.name} proxy $host:$port from $source" +
            (if (credentials != null) ", with a user name and password" else "") +
            (if (sslVerify) "" else ", without checking TLS certificates"),
    )

    return HttpClient(OkHttp) {
        installLogging()

        engine {
            // Unresolved, so that OkHttp looks the proxy's name up for each connection
            this.proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port))

            config {
                readTimeout(PROXIED_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                writeTimeout(PROXIED_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                if (credentials != null) {
                    val authorization = credentials.authorization

                    // OkHttp asks before each CONNECT, and after a 407, which means that the proxy refused them: no
                    // second try. Plain HTTP requests already had them, from the interceptor below.
                    proxyAuthenticator { _, response ->
                        val request = response.request

                        if (request.method == "CONNECT" && request.header(PROXY_AUTHORIZATION) == null) {
                            request.newBuilder().header(PROXY_AUTHORIZATION, authorization).build()
                        } else {
                            null
                        }
                    }

                    // A plain HTTP request goes to the proxy as it is, so it carries them; HTTPS goes inside a tunnel
                    addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val toHttpProxy = chain.connection()?.route()?.proxy?.type() == Proxy.Type.HTTP

                        if (!request.isHttps && toHttpProxy && request.header(PROXY_AUTHORIZATION) == null) {
                            chain.proceed(request.newBuilder().header(PROXY_AUTHORIZATION, authorization).build())
                        } else {
                            chain.proceed(request)
                        }
                    }
                }

                if (!sslVerify) {
                    val context = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(TrustAllCertificates), null)
                    }

                    sslSocketFactory(context.socketFactory, TrustAllCertificates)
                    hostnameVerifier { _, _ -> true }
                }
            }
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
