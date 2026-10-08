// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.net.Proxy
import java.net.URL

private const val REMOTE_URL = "https://example.invalid/team/project.git"

/** How an HTTP transport reports that the server accepted the credentials of a request. */
class AcceptedCredentialsTest {
    @Test
    fun `only a 2xx answer to a request with credentials is reported`() {
        // Whether the request has credentials, the server's status, and whether it's reported
        val cases = listOf(
            Triple(true, 200, true),
            Triple(true, 204, true),
            Triple(true, 302, false),
            Triple(true, 401, false),
            Triple(true, 500, false),
            Triple(false, 200, false),
        )

        assertAll(
            cases.map { (withCredentials, status, reported) ->
                Executable {
                    var reports = 0
                    val connection = mockk<HttpConnection>(relaxed = true) { every { responseCode } returns status }

                    openTransport().use { transport ->
                        transport.httpConnectionFactory = FixedFactory(connection)
                        transport.reportAcceptedCredentials { reports++ }
                        val wrapped = transport.httpConnectionFactory.create(URL("$REMOTE_URL/info/refs"))

                        wrapped.setRequestProperty("Accept", "*/*")
                        if (withCredentials) {
                            wrapped.setRequestProperty("Authorization", "Basic dXNlcjpwYXNz")
                        }

                        assertEquals(status, wrapped.responseCode)
                        assertEquals(if (reported) 1 else 0, reports, "credentials: $withCredentials, status: $status")
                        // A plain factory stays one, so that JGit applies http.sslVerify itself
                        assertFalse(transport.httpConnectionFactory is HttpConnectionFactory2)
                    }
                }
            }
        )
    }

    @Test
    fun `JGit's sessions configure the connections that its factory created`() {
        openTransport().use { transport ->
            transport.reportAcceptedCredentials {}
            val factory = transport.httpConnectionFactory

            assertTrue(factory is HttpConnectionFactory2)
            // JGit's own session refuses any other class of connection; this turns off TLS verification on it
            val connection = factory.create(URL("$REMOTE_URL/info/refs"))
            (factory as HttpConnectionFactory2).newSession().configure(connection, false)
        }
    }

    private fun openTransport() = Transport.open(URIish(REMOTE_URL)) as TransportHttp

    /** Creates [connection] for every URL. */
    private class FixedFactory(private val connection: HttpConnection) : HttpConnectionFactory {
        override fun create(url: URL): HttpConnection = connection
        override fun create(url: URL, proxy: Proxy?): HttpConnection = connection
    }
}
