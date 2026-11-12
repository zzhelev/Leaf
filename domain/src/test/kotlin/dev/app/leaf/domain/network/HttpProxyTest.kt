// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val A = "http://127.0.0.1:18081"
private const val B = "http://127.0.0.1:18082"
private const val HTTP_URL = "http://lfs.invalid/team/repo.git/info/lfs/objects/batch"
private const val HTTPS_URL = "https://lfs.invalid/team/repo.git/info/lfs/objects/batch"

/**
 * The proxy for a request, case by case as git-lfs 3.8 picks it (each case was run against git-lfs, which
 * `GitLfsProxyChoiceTest` in `data` repeats where git-lfs is installed).
 */
class HttpProxyTest {
    private fun proxy(port: Int, source: String, credentials: ProxyCredentials? = null) =
        ProxyChoice.Http("127.0.0.1", port, credentials, source)

    private fun choose(
        url: String,
        environment: Map<String, String> = emptyMap(),
        vararg git: Pair<String?, String>,
    ) = proxyFor(url, git.map { (subsection, value) -> ProxyConfigEntry(subsection, value) }, environment)

    @Test
    fun `nothing set is no proxy`() {
        assertEquals(ProxyChoice.Direct, choose(HTTP_URL))
        assertEquals(ProxyChoice.Direct, choose(HTTPS_URL))
    }

    @Test
    fun `an http request takes HTTP_PROXY, not HTTPS_PROXY`() {
        assertEquals(ProxyChoice.Direct, choose(HTTP_URL, mapOf("HTTPS_PROXY" to A)))
        assertEquals(proxy(18081, "HTTP_PROXY"), choose(HTTP_URL, mapOf("HTTP_PROXY" to A, "HTTPS_PROXY" to B)))
    }

    @Test
    fun `an https request takes HTTPS_PROXY, else HTTP_PROXY`() {
        assertEquals(proxy(18081, "HTTP_PROXY"), choose(HTTPS_URL, mapOf("HTTP_PROXY" to A)))
        assertEquals(proxy(18081, "HTTPS_PROXY"), choose(HTTPS_URL, mapOf("HTTPS_PROXY" to A, "HTTP_PROXY" to B)))
    }

    @Test
    fun `upper case variables come before lower case ones, and empty ones don't count`() {
        assertEquals(proxy(18081, "HTTPS_PROXY"), choose(HTTPS_URL, mapOf("HTTPS_PROXY" to A, "https_proxy" to B)))
        assertEquals(proxy(18082, "http_proxy"), choose(HTTP_URL, mapOf("http_proxy" to B)))
        assertEquals(proxy(18082, "https_proxy"), choose(HTTPS_URL, mapOf("HTTPS_PROXY" to "", "https_proxy" to B)))
    }

    @Test
    fun `ALL_PROXY doesn't count`() {
        assertEquals(ProxyChoice.Direct, choose(HTTP_URL, mapOf("ALL_PROXY" to A, "all_proxy" to A)))
        assertEquals(ProxyChoice.Direct, choose(HTTPS_URL, mapOf("ALL_PROXY" to A, "all_proxy" to A)))
    }

    @Test
    fun `git's http proxy comes before the variables, for http and https`() {
        assertEquals(proxy(18082, "http.proxy"), choose(HTTP_URL, mapOf("HTTP_PROXY" to A), null to B))
        assertEquals(proxy(18082, "http.proxy"), choose(HTTPS_URL, mapOf("HTTPS_PROXY" to A), null to B))
        assertEquals(proxy(18082, "http.proxy"), choose(HTTPS_URL, emptyMap(), null to B))
    }

    @Test
    fun `an empty http proxy leaves the variables`() {
        assertEquals(proxy(18081, "HTTP_PROXY"), choose(HTTP_URL, mapOf("HTTP_PROXY" to A), null to ""))
    }

    @Test
    fun `the last http proxy counts`() {
        assertEquals(proxy(18082, "http.proxy"), choose(HTTP_URL, emptyMap(), null to A, null to B))
    }

    @Test
    fun `a URL's proxy comes before http proxy, and an empty one hides it`() {
        assertEquals(
            proxy(18082, "http.http://lfs.invalid.proxy"),
            choose(HTTP_URL, emptyMap(), null to A, "http://lfs.invalid" to B),
        )
        assertEquals(
            proxy(18081, "HTTP_PROXY"),
            choose(HTTP_URL, mapOf("HTTP_PROXY" to A), null to B, "http://lfs.invalid" to ""),
        )
    }

    @Test
    fun `a URL matches by scheme, host, port, path and user as in git-lfs`() {
        fun matched(key: String, url: String = HTTP_URL) =
            choose(url, emptyMap(), null to A, key to B) == proxy(18082, "http.$key.proxy")

        assertTrue(matched("http://*.invalid"))
        assertTrue(matched("http://lfs.invalid/team"))
        assertTrue(matched("http://lfs.invalid/team/"))
        assertTrue(matched("http://lfs.invalid/team/repo"), "repo stands for repo.git before info/lfs")
        assertTrue(matched("http://lfs.invalid:80"))
        assertTrue(matched("http://bob@lfs.invalid", "http://bob@lfs.invalid/team/repo.git/info/lfs"))

        assertFalse(matched("http://lfs.invalid/other"))
        assertFalse(matched("http://lfs.invalid/te"), "a path matches in whole segments")
        assertFalse(matched("https://lfs.invalid"))
        assertFalse(matched("http://lfs.invalid:8080"))
        assertFalse(matched("http://bob@lfs.invalid"), "a user name in the key wants it in the request")
        assertFalse(matched("http://LFS.invalid"), "host names compare with their case, as in git-lfs")
        assertFalse(matched("http://*"), "a * stands for one label")
        assertFalse(matched("lfs.invalid"), "a key without a scheme matches nothing")
        assertFalse(matched("http://lfs.invalid/team/repo", "http://lfs.invalid/team/repo.git/x/y"))
    }

    @Test
    fun `the best URL wins`() {
        // A host without * over one with
        assertEquals(
            proxy(18081, "http.http://lfs.invalid.proxy"),
            choose(HTTP_URL, emptyMap(), "http://lfs.invalid" to A, "http://*.invalid" to B),
        )
        assertEquals(
            proxy(18081, "http.http://lfs.invalid.proxy"),
            choose(HTTP_URL, emptyMap(), "http://*.invalid" to B, "http://lfs.invalid" to A),
        )
        // A longer path, also when it comes first
        assertEquals(
            proxy(18082, "http.http://lfs.invalid/team/repo.git.proxy"),
            choose(HTTP_URL, emptyMap(), "http://lfs.invalid/team" to A, "http://lfs.invalid/team/repo.git" to B),
        )
        assertEquals(
            proxy(18082, "http.http://lfs.invalid/team/repo.git.proxy"),
            choose(HTTP_URL, emptyMap(), "http://lfs.invalid/team/repo.git" to B, "http://lfs.invalid/team" to A),
        )
        // The whole name over repo for repo.git
        assertEquals(
            proxy(18082, "http.http://lfs.invalid/team/repo.git.proxy"),
            choose(HTTP_URL, emptyMap(), "http://lfs.invalid/team/repo" to A, "http://lfs.invalid/team/repo.git" to B),
        )
        // A user name, at the same path
        assertEquals(
            proxy(18082, "http.http://bob@lfs.invalid.proxy"),
            choose("http://bob@lfs.invalid/x", emptyMap(), "http://lfs.invalid" to A, "http://bob@lfs.invalid" to B),
        )
        // The key's last value
        assertEquals(
            proxy(18082, "http.http://lfs.invalid.proxy"),
            choose(HTTP_URL, emptyMap(), "http://lfs.invalid" to A, "http://lfs.invalid" to B),
        )
    }

    @Test
    fun `NO_PROXY lists hosts that go directly`() {
        fun direct(noProxy: String, url: String = HTTP_URL, variable: String = "NO_PROXY") =
            choose(url, mapOf("HTTP_PROXY" to A, "HTTPS_PROXY" to A, variable to noProxy)) == ProxyChoice.Direct

        assertTrue(direct("lfs.invalid"))
        assertTrue(direct("invalid"), "a name matches its subdomains")
        assertTrue(direct(".invalid"))
        assertTrue(direct("*.invalid"))
        assertTrue(direct("*"))
        assertTrue(direct("lfs.invalid:80"))
        assertTrue(direct("lfs.invalid:443", HTTPS_URL), "a request without a port has its scheme's")
        assertTrue(direct("foo.com, LFS.Invalid ,bar", variable = "no_proxy"))

        assertFalse(direct(".lfs.invalid"), "a leading dot matches only subdomains")
        assertFalse(direct("*.lfs.invalid"))
        assertFalse(direct("lfs.invalid:443"))
        assertFalse(direct("fs.invalid"), "a name matches whole labels")
        assertFalse(direct(""))
    }

    @Test
    fun `NO_PROXY comes before no_proxy`() {
        val environment = mapOf("HTTP_PROXY" to A, "NO_PROXY" to "foo", "no_proxy" to "lfs.invalid")

        assertEquals(proxy(18081, "HTTP_PROXY"), choose(HTTP_URL, environment))
    }

    @Test
    fun `NO_PROXY applies to git's proxy too`() {
        assertEquals(ProxyChoice.Direct, choose(HTTP_URL, mapOf("NO_PROXY" to "lfs.invalid"), null to B))
    }

    @Test
    fun `NO_PROXY matches addresses and ranges`() {
        fun direct(noProxy: String, host: String = "10.255.255.1") =
            choose("http://$host/x", mapOf("HTTP_PROXY" to A, "NO_PROXY" to noProxy)) == ProxyChoice.Direct

        assertTrue(direct("10.0.0.0/8"))
        assertTrue(direct("10.255.255.1"))
        assertTrue(direct("10.255.255.1:80"))
        assertTrue(direct("fd00::/8", "[fd00::1]"))
        assertTrue(direct("fd00::1", "[fd00::1]"))

        assertFalse(direct("11.0.0.0/8"))
        assertFalse(direct("10.255.255.1:8080"))
        assertFalse(direct("10.0.0.0/8", "lfs.invalid"), "a range matches no name")
    }

    @Test
    fun `localhost and loopback addresses never go through a proxy`() {
        for (host in listOf("localhost", "localhost:8080", "127.0.0.1", "127.0.0.2:9", "[::1]", "LocalHost")) {
            assertEquals(ProxyChoice.Direct, choose("http://$host/x", mapOf("HTTP_PROXY" to A)), host)
        }
    }

    @Test
    fun `a proxy without a scheme is an HTTP proxy, with port 80 by default`() {
        assertEquals(proxy(18081, "HTTP_PROXY"), choose(HTTP_URL, mapOf("HTTP_PROXY" to "127.0.0.1:18081")))
        assertEquals(
            ProxyChoice.Http("proxy.example.com", 80, null, "HTTP_PROXY"),
            choose(HTTP_URL, mapOf("HTTP_PROXY" to "http://proxy.example.com")),
        )
        assertEquals(
            ProxyChoice.Http("proxy.example.com", 3128, null, "HTTP_PROXY"),
            choose(HTTP_URL, mapOf("HTTP_PROXY" to "HTTP://proxy.example.com:3128/")),
        )
    }

    @Test
    fun `a proxy's user name and password are decoded, and a user alone has an empty password`() {
        val choice = choose(HTTP_URL, mapOf("HTTP_PROXY" to "http://us%40er:p%3Ass@127.0.0.1:18081"))

        assertEquals(proxy(18081, "HTTP_PROXY", ProxyCredentials("us@er", "p:ss")), choice)
        // What git-lfs sends for it
        assertEquals("Basic dXNAZXI6cDpzcw==", ProxyCredentials("us@er", "p:ss").authorization)
        assertEquals("Basic dXNlcjo=", ProxyCredentials("user", "").authorization)
        // The UTF-8 bytes, as git-lfs sends a URL's
        assertEquals("Basic asO8cmdlbjpwdw==", ProxyCredentials("jürgen", "pw").authorization)
        assertEquals(
            proxy(18081, "HTTP_PROXY", ProxyCredentials("user", "")),
            choose(HTTP_URL, mapOf("HTTP_PROXY" to "http://user@127.0.0.1:18081")),
        )
    }

    @Test
    fun `credentials never show in text`() {
        val choice = choose(HTTP_URL, mapOf("HTTP_PROXY" to "http://alice:s3cret-pw@proxy.example.com:3128"))

        assertFalse("s3cret-pw" in choice.toString(), choice.toString())
        assertFalse("alice" in choice.toString(), choice.toString())

        val unusable = choose(HTTP_URL, mapOf("HTTP_PROXY" to "socks5://alice:s3cret-pw@proxy.example.com"))

        assertEquals(
            ProxyChoice.Unusable("socks5://proxy.example.com:1080", "HTTP_PROXY", UnusableProxyReason.SOCKS_CREDENTIALS),
            unusable,
        )
        assertFalse("s3cret-pw" in unusable.toString() || "alice" in unusable.toString())
    }

    @Test
    fun `SOCKS5 proxies, with port 1080 by default`() {
        assertEquals(
            ProxyChoice.Socks("proxy.example.com", 1080, "HTTPS_PROXY"),
            choose(HTTPS_URL, mapOf("HTTPS_PROXY" to "socks5://proxy.example.com")),
        )
        assertEquals(
            ProxyChoice.Socks("proxy.example.com", 9050, "http.proxy"),
            choose(HTTPS_URL, emptyMap(), null to "socks5h://proxy.example.com:9050"),
        )
    }

    @Test
    fun `proxies that Leaf can't use say why`() {
        fun unusable(proxy: String) = choose(HTTP_URL, mapOf("HTTP_PROXY" to proxy))

        assertEquals(
            ProxyChoice.Unusable("https://proxy.example.com:443", "HTTP_PROXY", UnusableProxyReason.HTTPS_PROXY),
            unusable("https://proxy.example.com"),
        )
        assertEquals(
            ProxyChoice.Unusable("socks4://proxy.example.com:80", "HTTP_PROXY", UnusableProxyReason.UNKNOWN_KIND),
            unusable("socks4://proxy.example.com"),
        )

        for (proxy in listOf("http://", "http://proxy:port", "http://proxy:0", "http://proxy:99999", "http://u%zz@p")) {
            val choice = unusable(proxy)

            assertTrue(choice is ProxyChoice.Unusable && choice.reason == UnusableProxyReason.UNREADABLE, proxy)
        }
    }

    @Test
    fun `IPv6 proxies and hosts`() {
        assertEquals(
            ProxyChoice.Http("fd00::2", 3128, null, "HTTP_PROXY"),
            choose("http://[fd00::1]/x", mapOf("HTTP_PROXY" to "http://[fd00::2]:3128")),
        )
    }

    @Test
    fun `requests that aren't http or https go directly`() {
        assertEquals(ProxyChoice.Direct, choose("ssh://lfs.invalid/x", mapOf("HTTP_PROXY" to A)))
        assertEquals(ProxyChoice.Direct, choose("not a url", mapOf("HTTP_PROXY" to A)))
    }

    @Test
    fun `URLs read as git-lfs reads them`() {
        val url = parseUrl("HTTPS://bob:pw@[fd00::1]:8443/a%20b/c?x=1#y")!!

        assertEquals("https", url.scheme)
        assertEquals("bob", url.user)
        assertEquals("fd00::1", url.host)
        assertEquals("8443", url.port)
        assertEquals(listOf("a b", "c"), url.pathSegments)
        assertNull(parseUrl("lfs.invalid/x"))
        assertNull(parseUrl("http://host:12ab/"))
    }
}
