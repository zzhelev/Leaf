// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64

/**
 * How one of Leaf's own HTTP requests (the built-in LFS client's, the update check's) reaches its server: directly or
 * through a proxy. [proxyFor] picks it.
 */
sealed interface ProxyChoice {
    data object Direct : ProxyChoice

    /**
     * An HTTP proxy at [host] and [port]. HTTPS goes through a `CONNECT` tunnel. [credentials], from the proxy's URL,
     * go to the proxy alone, in a Basic `Proxy-Authorization`. [source] names the setting: `HTTPS_PROXY`,
     * `http.proxy`, …
     */
    data class Http(val host: String, val port: Int, val credentials: ProxyCredentials?, val source: String) :
        ProxyChoice

    /** A SOCKS5 proxy, which resolves the server's name itself (as `socks5h` asks; git-lfs reads both alike). */
    data class Socks(val host: String, val port: Int, val source: String) : ProxyChoice

    /** A proxy that [source] names, and that Leaf can't use. [proxy] is its address, without user name or password. */
    data class Unusable(val proxy: String, val source: String, val reason: UnusableProxyReason) : ProxyChoice
}

enum class UnusableProxyReason {
    /** `https://`: Leaf speaks only plain HTTP to a proxy. */
    HTTPS_PROXY,

    /** A SOCKS proxy with a user name or password, which Leaf can't send. */
    SOCKS_CREDENTIALS,

    /** Another scheme, such as `socks4://`. */
    UNKNOWN_KIND,

    /** Not an address: no host, a port that isn't a number, a bad `%` escape. */
    UNREADABLE,
}

/** The user name and password of a proxy's URL. Neither shows in [toString], so that no log or error has them. */
class ProxyCredentials(val user: String, val password: String) {
    /** The `Proxy-Authorization` value: Basic, of the UTF-8 bytes of `user:password`, as git-lfs sends it. */
    val authorization: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean =
        other is ProxyCredentials && other.user == user && other.password == password

    override fun hashCode(): Int = 31 * user.hashCode() + password.hashCode()

    override fun toString(): String = "ProxyCredentials(hidden)"
}

/** What a request needs to reach its server: whether to check its TLS certificate (`http.sslVerify`), and how. */
data class HttpRequestSettings(val sslVerify: Boolean, val proxy: ProxyChoice)

/**
 * A git config entry about proxies: `http.proxy` when [subsection] is null, else `http.<subsection>.proxy`. An entry
 * without a value, or with an empty one, has the [value] `""`.
 */
data class ProxyConfigEntry(val subsection: String?, val value: String) {
    val key: String get() = if (subsection == null) "http.proxy" else "http.$subsection.proxy"
}

private val PROXY_VARIABLES_HTTPS = listOf("HTTPS_PROXY", "https_proxy")
private val PROXY_VARIABLES_HTTP = listOf("HTTP_PROXY", "http_proxy")
private val NO_PROXY_VARIABLES = listOf("NO_PROXY", "no_proxy")

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

/**
 * The proxy that git-lfs would use for a request to [url] (git-lfs 3.8, `lfshttp/proxy.go`, checked against it by
 * `GitLfsProxyChoiceTest`), from git's [gitEntries] in the order git reads them and the [environment]:
 * - an `https` request takes `HTTPS_PROXY`, else `https_proxy`, else what an `http` request takes: `HTTP_PROXY`, else
 *   `http_proxy`. A variable set to nothing counts as not set, and `ALL_PROXY` doesn't count;
 * - git's `http.<url>.proxy` for the best matching URL ([gitProxyFor]), else `http.proxy`, comes before both, unless
 *   it's empty: then the variables apply, and an empty `http.<url>.proxy` also hides `http.proxy`;
 * - `NO_PROXY`, else `no_proxy`, lists the hosts that go directly ([bypassesProxy]), and so do `localhost` and loopback
 *   addresses.
 */
fun proxyFor(url: String, gitEntries: List<ProxyConfigEntry>, environment: Map<String, String>): ProxyChoice {
    val request = parseUrl(url) ?: return ProxyChoice.Direct

    if (request.scheme != "http" && request.scheme != "https") {
        return ProxyChoice.Direct
    }

    var httpsProxy = firstSet(environment, PROXY_VARIABLES_HTTPS)
    var httpProxy = firstSet(environment, PROXY_VARIABLES_HTTP)
    val gitProxy = gitProxyFor(request, gitEntries)

    if (gitProxy != null && gitProxy.value.isNotEmpty()) {
        val setting = gitProxy.key to gitProxy.value

        if (request.scheme == "https") {
            httpsProxy = setting
        }

        httpProxy = setting
    }

    val (source, proxy) = (if (request.scheme == "https") httpsProxy else null) ?: httpProxy ?: return ProxyChoice.Direct
    val noProxy = firstSet(environment, NO_PROXY_VARIABLES)?.second.orEmpty()

    if (bypassesProxy(request, noProxy)) {
        return ProxyChoice.Direct
    }

    return parseProxy(proxy, source)
}

/** The first of [names] that [environment] sets to something, with its value. */
private fun firstSet(environment: Map<String, String>, names: List<String>): Pair<String, String>? =
    names.firstNotNullOfOrNull { name -> environment[name]?.takeIf { it.isNotEmpty() }?.let { name to it } }

/**
 * The entry for [request]: the `http.<url>.proxy` whose URL matches best (git-lfs's `URLConfig.Get`), else the last
 * `http.proxy`. A URL matches when its scheme and port are the request's, its host has as many labels with `*`
 * standing for any, its path is a start of the request's in whole segments (`repo` stands for `repo.git` before
 * `info/lfs`), and a user name in it is the request's. A host without `*` beats one with, then a longer path, then a
 * user name. Names compare with their case, as in git-lfs.
 */
internal fun gitProxyFor(request: ParsedUrl, entries: List<ProxyConfigEntry>): ProxyConfigEntry? {
    var best: Pair<ProxyConfigEntry, Triple<Int, Int, Int>>? = null

    for (entry in entries) {
        val subsection = entry.subsection ?: continue
        val configUrl = parseUrl(subsection) ?: continue

        if (configUrl.scheme != request.scheme) {
            continue
        }

        val hostScore = hostScore(request.host, configUrl.host)

        if (hostScore == 0 || hostScore < (best?.second?.first ?: 0)) {
            continue
        }

        if (request.portOrDefault != configUrl.portOrDefault) {
            continue
        }

        val pathScore = pathScore(request.pathSegments, configUrl.pathSegments)

        if (pathScore == 0) {
            continue
        }

        val userScore = when {
            configUrl.user == null -> 0
            configUrl.user == request.user -> 1
            else -> continue
        }

        val current = best?.second
        val isBetter = current == null ||
            hostScore > current.first ||
            pathScore > current.second ||
            (pathScore == current.second && userScore > current.third)

        if (isBetter) {
            best = entry to Triple(hostScore, pathScore, userScore)
        }
    }

    val bestEntry = best?.first

    // The last value of that key, as git reads the files in order
    return if (bestEntry != null) {
        entries.last { it.subsection == bestEntry.subsection }
    } else {
        entries.lastOrNull { it.subsection == null }
    }
}

private fun hostScore(requestHost: String, configHost: String): Int {
    val requestLabels = requestHost.split(".")
    val configLabels = configHost.split(".")

    if (requestLabels.size != configLabels.size) {
        return 0
    }

    var score = requestLabels.size + 1

    for ((index, label) in requestLabels.withIndex()) {
        when (configLabels[index]) {
            "*" -> score--
            label -> {}
            else -> return 0
        }
    }

    return score
}

private fun pathScore(requestPath: List<String>, configPath: List<String>): Int {
    if (requestPath.size < configPath.size) {
        return 0
    }

    var score = 1

    for ((index, element) in configPath.withIndex()) {
        val requestElement = requestPath[index]

        score += when {
            element == requestElement -> 2
            isRepositoryBeforeLfs(requestPath, index) && requestElement.removeSuffix(".git") == element -> 1
            else -> return 0
        }
    }

    return score
}

/** Whether the path's [index]th segment is `<name>.git`, followed by `info/lfs`. */
private fun isRepositoryBeforeLfs(path: List<String>, index: Int): Boolean {
    val segment = path[index]

    return segment.length >= 5 && segment.endsWith(".git") && index + 2 < path.size &&
        path[index + 1] == "info" && path[index + 2] == "lfs"
}

/**
 * Whether [request] goes directly rather than through the proxy, by [noProxy]'s rules (Go's `httpproxy`, which
 * git-lfs uses): `localhost` and loopback addresses always do. The list is separated by commas; `*` matches every
 * host; `example.com` matches it and its subdomains, `.example.com` and `*.example.com` its subdomains only; an IP
 * address or a CIDR range matches addresses; and a `:port` after a name or address limits it to that port.
 */
internal fun bypassesProxy(request: ParsedUrl, noProxy: String): Boolean {
    val host = request.host.lowercase()
    val port = request.portOrDefault
    val address = ipLiteral(host)

    if (host == "localhost" || address?.isLoopbackAddress == true) {
        return true
    }

    for (raw in noProxy.split(",")) {
        val pattern = raw.trim().lowercase()

        if (pattern.isEmpty()) {
            continue
        }

        if (pattern == "*") {
            return true
        }

        val range = cidr(pattern)

        if (range != null) {
            if (address != null && range.contains(address)) {
                return true
            }
            continue
        }

        val (patternHost, patternPort) = splitHostPort(pattern) ?: (pattern to null)

        if (patternHost.isEmpty()) {
            continue
        }

        val patternAddress = ipLiteral(patternHost)

        if (patternAddress != null) {
            if (address != null && address == patternAddress && (patternPort == null || patternPort == port)) {
                return true
            }
            continue
        }

        val withoutStar = if (patternHost.startsWith("*.")) patternHost.substring(1) else patternHost
        val matchesHostItself = !withoutStar.startsWith(".")
        val suffix = if (matchesHostItself) ".$withoutStar" else withoutStar
        val nameMatches = host.endsWith(suffix) || (matchesHostItself && host == suffix.substring(1))

        if (nameMatches && (patternPort == null || patternPort == port)) {
            return true
        }
    }

    return false
}

/** `host:port` or `[host]:port` split as Go's `net.SplitHostPort` does, or null when there is no port part. */
private fun splitHostPort(text: String): Pair<String, String>? {
    if (text.startsWith("[")) {
        val end = text.indexOf("]:")
        return if (end < 0) null else text.substring(1, end) to text.substring(end + 2)
    }

    val colon = text.lastIndexOf(':')

    if (colon < 0 || text.indexOf(':') != colon) {
        return null
    }

    return text.substring(0, colon) to text.substring(colon + 1)
}

private class Cidr(val network: ByteArray, val prefixLength: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address

        if (bytes.size != network.size) {
            return false
        }

        for (bit in 0 until prefixLength) {
            val mask = 0x80 ushr (bit % 8)

            if ((bytes[bit / 8].toInt() and mask) != (network[bit / 8].toInt() and mask)) {
                return false
            }
        }

        return true
    }
}

private fun cidr(text: String): Cidr? {
    val slash = text.indexOf('/')

    if (slash < 0) {
        return null
    }

    val address = ipLiteral(text.substring(0, slash)) ?: return null
    val prefixLength = text.substring(slash + 1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    if (prefixLength == null || prefixLength > address.address.size * 8) {
        return null
    }

    return Cidr(address.address, prefixLength)
}

/** An IP address written in [text], or null for a name. Never looks a name up. */
private fun ipLiteral(text: String): InetAddress? {
    val parts = text.split(".")

    if (parts.size == 4 && parts.all { it.isDecimalOctet() }) {
        return Inet4Address.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
    }

    if (':' in text) {
        return try {
            Inet6Address.ofLiteral(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    return null
}

private fun String.isDecimalOctet(): Boolean =
    isNotEmpty() && length <= 3 && all(Char::isDigit) && (length == 1 || first() != '0') && toInt() <= 255

/**
 * The proxy that [text] names, read as git-lfs reads it: a URL, or `host:port`, which is an HTTP proxy; port 80 for
 * HTTP and 1080 for SOCKS when none is given; a user name and password in it, `%`-decoded, for the proxy.
 */
internal fun parseProxy(text: String, source: String): ProxyChoice {
    val withScheme = if (SCHEME.containsMatchIn(text)) text else "http://$text"
    val url = parseUrl(withScheme)
    val scheme = url?.scheme ?: withScheme.substringBefore("://").lowercase()
    val defaultPort = when (scheme) {
        "socks5", "socks5h" -> 1080
        "https" -> 443
        else -> 80
    }
    val port = url?.port?.toIntOrNull() ?: defaultPort
    val shown = if (url != null && url.host.isNotEmpty()) "$scheme://${url.hostForUrl}:$port" else "$scheme://…"

    fun unusable(reason: UnusableProxyReason) = ProxyChoice.Unusable(shown, source, reason)

    if (url == null || url.host.isEmpty() || port !in 1..65535) {
        return unusable(UnusableProxyReason.UNREADABLE)
    }

    val credentials = url.userInfo?.let { userInfo ->
        val user = percentDecode(userInfo.substringBefore(':'))
        val password = if (':' in userInfo) percentDecode(userInfo.substringAfter(':')) else ""

        if (user == null || password == null) {
            return unusable(UnusableProxyReason.UNREADABLE)
        }

        ProxyCredentials(user, password)
    }

    return when (scheme) {
        "http" -> ProxyChoice.Http(url.host, port, credentials, source)
        "https" -> unusable(UnusableProxyReason.HTTPS_PROXY)
        "socks5", "socks5h" -> if (credentials != null) {
            unusable(UnusableProxyReason.SOCKS_CREDENTIALS)
        } else {
            ProxyChoice.Socks(url.host, port, source)
        }

        else -> unusable(UnusableProxyReason.UNKNOWN_KIND)
    }
}

/**
 * A URL's parts as git-lfs compares them: [scheme] in lower case, [user] `%`-decoded, [host] without brackets, [port]
 * as written (empty when there is none), and the path's segments, `%`-decoded.
 */
internal data class ParsedUrl(
    val scheme: String,
    val userInfo: String?,
    val user: String?,
    val host: String,
    val port: String,
    val pathSegments: List<String>,
) {
    val portOrDefault: String
        get() = port.ifEmpty {
            when (scheme) {
                "http" -> "80"
                "https" -> "443"
                "ssh" -> "22"
                else -> ""
            }
        }

    val hostForUrl: String get() = if (':' in host) "[$host]" else host
}

/** [text] as a URL with `scheme://`, or null when it has none or doesn't read as one. */
internal fun parseUrl(text: String): ParsedUrl? {
    val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
    val rest = text.substring(scheme.length + 3)
    val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')

    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at) else null
    val hostPort = authority.substring(at + 1)

    val (host, port) = if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']')

        if (end < 0) {
            return null
        }

        val afterHost = hostPort.substring(end + 1)

        if (afterHost.isNotEmpty() && !afterHost.startsWith(":")) {
            return null
        }

        hostPort.substring(1, end) to afterHost.removePrefix(":")
    } else {
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) hostPort to "" else hostPort.substring(0, colon) to hostPort.substring(colon + 1)
    }

    if (!port.all(Char::isDigit)) {
        return null
    }

    val segments = path.split('/').filter { it.isNotEmpty() }.map { percentDecode(it) ?: return null }
    val user = userInfo?.let { percentDecode(it.substringBefore(':')) ?: return null }

    return ParsedUrl(scheme, userInfo, user, host, port, segments)
}

/** [text] with its `%XX` escapes decoded as UTF-8, or null for a bad escape. A `+` stays a `+`. */
private fun percentDecode(text: String): String? {
    if ('%' !in text) {
        return text
    }

    val bytes = java.io.ByteArrayOutputStream()
    var index = 0

    while (index < text.length) {
        val char = text[index]

        if (char == '%') {
            val hex = text.substring(index + 1, minOf(index + 3, text.length))

            if (hex.length != 2 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return null
            }

            bytes.write(hex.toInt(16))
            index += 3
        } else {
            bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }
    }

    return bytes.toString(Charsets.UTF_8)
}
