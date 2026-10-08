// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION
import java.net.Proxy
import java.net.URL

/**
 * Makes this transport call [onAccepted] whenever the server answers a request that carried credentials with success
 * (2xx). Git approves credentials then (`handle_curl_result` in git's http.c), at the first request that succeeds, not
 * once the whole operation has, and JGit doesn't tell its [org.eclipse.jgit.transport.CredentialsProvider] that the
 * credentials worked. Call it before the transport opens a connection.
 */
internal fun TransportHttp.reportAcceptedCredentials(onAccepted: () -> Unit) {
    val factory = httpConnectionFactory

    httpConnectionFactory = if (factory is HttpConnectionFactory2) {
        AcceptedCredentialsConnectionFactory2(factory, onAccepted)
    } else {
        AcceptedCredentialsConnectionFactory(factory, onAccepted)
    }
}

/** Creates [delegate]'s connections, wrapped so that they call [onAccepted]. */
private open class AcceptedCredentialsConnectionFactory(
    private val delegate: HttpConnectionFactory,
    private val onAccepted: () -> Unit,
) : HttpConnectionFactory {
    override fun create(url: URL): HttpConnection =
        AcceptedCredentialsConnection(delegate.create(url), onAccepted)

    override fun create(url: URL, proxy: Proxy?): HttpConnection =
        AcceptedCredentialsConnection(delegate.create(url, proxy), onAccepted)
}

/**
 * Like [AcceptedCredentialsConnectionFactory], for a factory whose sessions configure each connection, such as JGit's
 * default one, which turns off TLS verification with `http.sslVerify = false`. Its sessions get the connection that
 * [delegate] created, as they may need its class.
 */
private class AcceptedCredentialsConnectionFactory2(
    private val delegate: HttpConnectionFactory2,
    onAccepted: () -> Unit,
) : AcceptedCredentialsConnectionFactory(delegate, onAccepted), HttpConnectionFactory2 {
    override fun newSession(): HttpConnectionFactory2.GitSession {
        val session = delegate.newSession()

        return object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection, sslVerify: Boolean): HttpConnection {
                session.configure((connection as AcceptedCredentialsConnection).delegate, sslVerify)
                return connection
            }

            override fun close() = session.close()
        }
    }
}

/** [delegate], calling [onAccepted] when it gets a 2xx answer to a request that carried credentials. */
private class AcceptedCredentialsConnection(
    val delegate: HttpConnection,
    private val onAccepted: () -> Unit,
) : HttpConnection by delegate {
    private var hasCredentials = false

    override fun setRequestProperty(key: String, value: String) {
        if (key.equals(HDR_AUTHORIZATION, ignoreCase = true)) {
            hasCredentials = true
        }

        delegate.setRequestProperty(key, value)
    }

    override fun getResponseCode(): Int {
        val code = delegate.responseCode

        if (hasCredentials && code in 200..299) {
            onAccepted()
        }

        return code
    }
}
