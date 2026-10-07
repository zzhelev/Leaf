// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.URIish
import java.io.ByteArrayOutputStream

private const val CREDENTIAL_SECTION = "credential"
private val URL_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
private val URL_HOST = Regex("[A-Za-z0-9._\\-\\[\\]:*]+")

/**
 * What git knows about a URL when it talks to credential helpers (`credential_from_url` in git's credential.c), so
 * that Leaf and the git CLI find each other's credentials:
 * - [protocol], the URL's scheme;
 * - [host], with the port when the URL has one, such as `example.com:8443`;
 * - [path], without its leading and trailing slashes and decoded the way git decodes it, such as `team/project.git`;
 * - [username], the user name in the URL.
 */
private data class CredentialAttributes(
    val protocol: String?,
    val host: String?,
    val path: String?,
    val username: String?,
) {
    /** Git refuses a URL with a newline in one of these (`check_url_component`). */
    val hasNewline get() = listOf(protocol, host, path, username).any { it != null && '\n' in it }
}

/** The attributes of [uri], the URL that JGit asks credentials for. */
private fun credentialAttributes(uri: URIish) = CredentialAttributes(
    protocol = uri.scheme,
    host = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host,
    // Not uri.path, which JGit has decoded its own way
    path = credentialPath(uri.rawPath),
    username = uri.user,
)

/**
 * The input that git writes to a credential helper for [uri] (`credential_write`): `protocol`, `host`, `path` with
 * [useHttpPath], and [username] and [password] when given. Get, store and erase all send this, so that each finds what
 * the others, or the git CLI, saved.
 *
 * Null where git refuses to run the helper: the URL has a newline, or a value to send has a newline or a carriage
 * return (unless `credential.protectProtocol` is off). Either could add a line, such as a second `host`, and get
 * another server's credentials.
 */
internal fun credentialHelperInput(
    uri: URIish,
    useHttpPath: Boolean,
    username: String? = null,
    password: String? = null,
): String? {
    val attributes = credentialAttributes(uri)

    val values = listOf(
        "protocol" to attributes.protocol,
        "host" to attributes.host,
        "path" to attributes.path?.takeIf { useHttpPath },
        "username" to username,
        "password" to password,
    ).mapNotNull { (key, value) -> value?.let { key to it } }

    if (attributes.hasNewline || values.any { (_, value) -> '\n' in value || '\r' in value }) {
        return null
    }

    return values.joinToString("") { (key, value) -> "$key=$value\n" }
}

/**
 * The path that git gives helpers for a URL whose path, still encoded, is [rawPath]: without its leading and trailing
 * slashes, decoded by [gitUrlDecode]. Null if nothing is left.
 */
private fun credentialPath(rawPath: String?): String? {
    val path = rawPath.orEmpty().trimStart('/')

    if (path.isEmpty()) {
        return null
    }

    // Git trims the trailing slashes after decoding, and never the first character
    val decoded = gitUrlDecode(path)

    return decoded.take(1) + decoded.drop(1).trimEnd('/')
}

/**
 * Decodes `%XX` the way git decodes a URL's parts (`url_decode` in git's url.c), which leaves alone what comes before
 * the first colon, `%00`, and invalid escapes: `a%20b:c%20d` becomes `a%20b:c d`.
 */
private fun gitUrlDecode(text: String): String {
    val colon = text.indexOf(':')

    return if (colon > 0) {
        text.substring(0, colon) + percentDecode(text.substring(colon))
    } else {
        percentDecode(text)
    }
}

/** Decodes the `%XX` escapes in [text], except `%00` and invalid ones (`url_percent_decode` in git's url.c). */
private fun percentDecode(text: String): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val decoded = ByteArrayOutputStream(bytes.size)
    var i = 0

    while (i < bytes.size) {
        val value = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) {
            hexByte(bytes[i + 1], bytes[i + 2])
        } else {
            -1
        }

        if (value > 0) {
            decoded.write(value)
            i += 3
        } else {
            decoded.write(bytes[i].toInt())
            i++
        }
    }

    return decoded.toString(Charsets.UTF_8)
}

/** The byte that the hex digits [high] and [low] stand for, or -1 if one isn't a hex digit. */
private fun hexByte(high: Byte, low: Byte): Int {
    val highValue = hexValue(high)
    val lowValue = hexValue(low)

    return if (highValue >= 0 && lowValue >= 0) highValue * 16 + lowValue else -1
}

private fun hexValue(byte: Byte): Int = when (val char = byte.toInt().toChar()) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/**
 * The credential settings that git applies to a URL (`credential_apply_config` in git's credential.c):
 * - [helpers], the `credential.helper` values, in the order git runs them;
 * - [useHttpPath], whether helpers get the URL's path;
 * - [username], the user name that git already knows and sends the helpers: the URL's, or else
 *   `credential.username`.
 */
data class CredentialSettings(
    val helpers: List<String>,
    val useHttpPath: Boolean,
    val username: String?,
)

/**
 * A config entry as `git config --list` gives it: its [key], such as `credential.https://example.com.helper`, and its
 * [value], which is null for a key written without `=`.
 */
internal data class ConfigEntry(val key: String, val value: String?)

/** The entries that `git config --list -z` prints: each is the key, then a newline and the value if it has one. */
internal fun parseConfigList(output: String): List<ConfigEntry> = output
    .split('\u0000')
    .filter { it.isNotEmpty() }
    .map { entry ->
        val newline = entry.indexOf('\n')

        if (newline < 0) {
            ConfigEntry(entry, null)
        } else {
            ConfigEntry(entry.substring(0, newline), entry.substring(newline + 1))
        }
    }

/**
 * The credential settings that [entries], in the order git reads them (system, global, local and worktree config,
 * each from top to bottom), give [uri]. Like git, every `credential.<name>`, and every `credential.<url>.<name>` whose
 * URL applies to [uri] ([credentialUrlApplies]), counts in turn: a `helper` joins the list, or clears it when it's
 * empty, and a later `useHttpPath` or `username` replaces an earlier one.
 */
internal fun credentialSettings(entries: List<ConfigEntry>, uri: URIish): CredentialSettings {
    val helpers = mutableListOf<String>()
    var useHttpPath = false
    var configUsername: String? = null

    for ((key, value) in entries) {
        if (!key.startsWith("$CREDENTIAL_SECTION.", ignoreCase = true)) {
            continue
        }

        val rest = key.substring(CREDENTIAL_SECTION.length + 1)
        val lastDot = rest.lastIndexOf('.')

        // Git stops at a setting without a value, or at a boolean it can't read; Leaf skips them
        if (value == null || (lastDot >= 0 && !credentialUrlApplies(rest.substring(0, lastDot), uri))) {
            continue
        }

        when (rest.substring(lastDot + 1).lowercase()) {
            "helper" -> if (value.isEmpty()) helpers.clear() else helpers += value
            "username" -> configUsername = value
            "usehttppath" -> gitBoolean(value)?.let { useHttpPath = it }
        }
    }

    return CredentialSettings(
        helpers = helpers,
        useHttpPath = useHttpPath,
        // credential.username doesn't replace the URL's own
        username = uri.user?.takeIf { it.isNotEmpty() } ?: configUsername,
    )
}

/**
 * The `credential.*` entries of [config] that apply to [uri], for when git can't list them. JGit doesn't keep the
 * order of different subsections, so the general ones come first, then those of the subsections that apply, from the
 * least specific to the most. Each key keeps the order of the config files. JGit also skips `includeIf`.
 */
internal fun jgitCredentialEntries(config: Config, uri: URIish): List<ConfigEntry> {
    val subsections = listOf<String?>(null) + credentialConfigSubsections(config, uri).reversed()

    return subsections.flatMap { subsection ->
        listOf("helper", "username", "useHttpPath").flatMap { name ->
            config.getStringList(CREDENTIAL_SECTION, subsection, name).map { value ->
                // JGit gives null for an empty value (`helper =`), and an empty string for a key without `=`
                val gitValue = if (value == null) "" else value.ifEmpty { null }

                ConfigEntry(listOfNotNull(CREDENTIAL_SECTION, subsection, name).joinToString("."), gitValue)
            }
        }
    }
}

/** A git boolean (`git_config_bool`), or null if git can't read it. */
internal fun gitBoolean(value: String): Boolean? = when (value.lowercase()) {
    "true", "yes", "on" -> true
    "", "false", "no", "off" -> false
    else -> value.toLongOrNull()?.let { it != 0L }
}

/**
 * Whether git applies the settings `credential.<url>.<name>` to [uri] (urlmatch.c):
 * - a URL applies when it has the remote's scheme, host and port, ignoring case and the scheme's default port. `*` in
 *   its host stands for one name between dots. Its path is the remote's or a folder above it, and a user name in it
 *   must be the remote's. So `https://example.com` doesn't apply to `https://example.com:8443/team/project.git`, but
 *   `https://example.com:8443/team` does.
 * - anything else is a partial URL, such as `example.com:8443` or `https://`, whose parts must equal the remote's
 *   (`match_partial_url`).
 */
internal fun credentialUrlApplies(url: String, uri: URIish): Boolean {
    val normalized = normalizeUrl(url)

    return if (normalized != null) {
        normalized.match(normalizedRemote(uri)) != null
    } else {
        partialUrlMatches(url, credentialAttributes(uri))
    }
}

/**
 * The subsections of `credential` in [config] that apply to [uri] ([credentialUrlApplies]), the most specific first:
 * the longest host, then the longest path, then one with a user name, and partial URLs last.
 */
internal fun credentialConfigSubsections(config: Config, uri: URIish): List<String> {
    val attributes = credentialAttributes(uri)
    val remote = normalizedRemote(uri)

    val subsections = config.getSubsections(CREDENTIAL_SECTION)

    val urlMatches = subsections
        .mapNotNull { subsection -> normalizeUrl(subsection)?.match(remote)?.let { subsection to it } }
        .sortedWith(
            compareByDescending<Pair<String, UrlMatch>> { (_, match) -> match.hostLength }
                .thenByDescending { (_, match) -> match.pathLength }
                .thenByDescending { (_, match) -> match.userMatched }
        )
        .map { (subsection, _) -> subsection }

    val partialMatches = subsections.filter { subsection ->
        normalizeUrl(subsection) == null && partialUrlMatches(subsection, attributes)
    }

    return urlMatches + partialMatches
}

/** A URL normalized the way git compares URLs in config keys: lowercase scheme and host, no default port. */
private class NormalizedUrl(
    val scheme: String,
    val user: String?,
    val host: String,
    val port: Int?,
    val path: String,
)

/** [uri] as git compares it with the URLs in config keys, from what it gives helpers (`credential_format`). */
private fun normalizedRemote(uri: URIish): NormalizedUrl {
    val attributes = credentialAttributes(uri)
    val scheme = uri.scheme.orEmpty().lowercase()

    return NormalizedUrl(
        scheme = scheme,
        user = attributes.username,
        host = uri.host.orEmpty().lowercase(),
        port = uri.port.takeIf { it > 0 && it != defaultPort(scheme) },
        path = "/" + attributes.path.orEmpty(),
    )
}

/** How closely a config URL matches the remote, to pick the most specific one (`cmp_matches` in urlmatch.c). */
private class UrlMatch(val hostLength: Int, val pathLength: Int, val userMatched: Boolean)

private fun defaultPort(scheme: String) = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}

/**
 * [url] normalized as git normalizes it (`url_normalize`), or null if git doesn't take it as a URL. Unlike git, it
 * leaves `.` and `..` in the path.
 */
private fun normalizeUrl(url: String): NormalizedUrl? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")

    if (!URL_SCHEME.matches(scheme)) {
        return null
    }

    val rest = url.substring(scheme.length + 3)
    val pathStart = rest.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: rest.length
    val authority = rest.substring(0, pathStart)
    val at = authority.indexOf('@')
    val user = if (at >= 0) percentDecode(authority.substring(0, at).substringBefore(':')) else null

    // The port follows the last colon, unless that colon is part of an IPv6 address such as [::1]
    val hostAndPort = authority.substring(at + 1)
    val portColon = hostAndPort.lastIndexOf(':').takeIf { it > hostAndPort.lastIndexOf(']') } ?: -1
    val host = if (portColon >= 0) hostAndPort.substring(0, portColon) else hostAndPort

    if (!URL_HOST.matches(host)) {
        return null
    }

    val portText = if (portColon >= 0) hostAndPort.substring(portColon + 1) else ""
    val port = if (portText.isEmpty()) {
        null
    } else {
        // Leading zeros don't count, and git refuses a port it can't use
        portText.trimStart('0')
            .takeIf { digits -> portText.all { it in '0'..'9' } && digits.length in 1..5 }
            ?.toInt()
            ?.takeIf { it in 1..65535 }
            ?: return null
    }

    return NormalizedUrl(
        scheme = scheme.lowercase(),
        user = user,
        host = host.lowercase(),
        port = port.takeIf { it != defaultPort(scheme.lowercase()) },
        path = percentDecode(rest.substring(pathStart)).let { if (it.startsWith("/")) it else "/$it" },
    )
}

/** How this config URL matches [remote] (`match_urls` in urlmatch.c), or null if it doesn't. */
private fun NormalizedUrl.match(remote: NormalizedUrl): UrlMatch? {
    if (scheme != remote.scheme || (user != null && user != remote.user) || port != remote.port) {
        return null
    }

    val hostLabels = remote.host.split('.')
    val patternLabels = host.split('.')
    val hostMatches = hostLabels.size == patternLabels.size &&
            hostLabels.zip(patternLabels).all { (label, pattern) -> pattern == "*" || pattern == label }

    if (!hostMatches) {
        return null
    }

    // The path matches if it's the same, or a folder above the remote's (url_match_prefix)
    val pathLength = when {
        path == "/" -> 1
        path.removeSuffix("/").let { remote.path == it || remote.path.startsWith("$it/") } ->
            path.removeSuffix("/").length + 1

        else -> return null
    }

    return UrlMatch(hostLength = host.length, pathLength = pathLength, userMatched = user != null)
}

/**
 * Whether the partial URL [url] matches the remote's [attributes] (`match_partial_url` in credential.c): each part it
 * has, among protocol, host, path and user name, must be the same.
 */
private fun partialUrlMatches(url: String, attributes: CredentialAttributes): Boolean {
    val wanted = partialUrlAttributes(url)

    // Git skips such a key, with a warning
    return !wanted.hasNewline &&
            (wanted.protocol == null || wanted.protocol == attributes.protocol) &&
            (wanted.host == null || wanted.host == attributes.host) &&
            (wanted.path == null || wanted.path == attributes.path) &&
            (wanted.username == null || wanted.username == attributes.username)
}

/** The parts of the partial URL [url] (`credential_from_potentially_partial_url` in credential.c). */
private fun partialUrlAttributes(url: String): CredentialAttributes {
    val protocolEnd = url.indexOf("://")
    val start = if (protocolEnd >= 0) protocolEnd + 3 else 0
    val slash = url.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: url.length
    val at = url.indexOf('@', start)
    val colon = url.indexOf(':', start)

    val (username, hostStart) = when {
        at < 0 || slash <= at -> null to start
        colon < 0 || at <= colon -> gitUrlDecode(url.substring(start, at)) to at + 1
        else -> gitUrlDecode(url.substring(start, colon)) to at + 1
    }

    return CredentialAttributes(
        protocol = url.substring(0, protocolEnd.coerceAtLeast(0)).ifEmpty { null },
        host = url.substring(hostStart, slash).ifEmpty { null }?.let(::gitUrlDecode),
        path = credentialPath(url.substring(slash)),
        username = username,
    )
}
