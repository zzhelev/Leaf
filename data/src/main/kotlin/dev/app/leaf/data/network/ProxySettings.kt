// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.credentials.parseConfigList
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.domain.network.ProxyConfigEntry
import dev.app.leaf.domain.network.proxyFor
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ProxySettings"

private const val HTTP_SECTION = "http"
private const val PROXY_NAME = "proxy"

private val CONFIG_TIMEOUT = 10.seconds

/**
 * The proxy for each of Leaf's own HTTP requests (fork-only): the one that git-lfs would use for its URL
 * ([proxyFor]), from git's `http.<url>.proxy` and `http.proxy` and from the environment that the login shell sets up
 * (`HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY`, …).
 */
class ProxySettings(
    private val environment: suspend () -> Map<String, String>,
    private val userConfigEntries: suspend () -> List<ProxyConfigEntry>,
) {
    @Inject
    constructor(loginShellEnvironment: LoginShellEnvironment, gitCli: GitCli) : this(
        environment = { processEnvironment(loginShellEnvironment.variables()) },
        userConfigEntries = { userProxyEntries(gitCli) },
    )

    /**
     * The proxy for a request to [url] made for a repository whose config is [config]: the repository's config as JGit
     * opens it in Leaf, with the files that `includeIf` names, as git reads them.
     */
    suspend fun forRequest(config: Config, url: String): ProxyChoice = proxyFor(url, config.proxyEntries(), environment())

    /** The proxy for a request to [url] that no repository makes, such as the update check: the user's config. */
    suspend fun forRequestWithoutRepository(url: String): ProxyChoice =
        proxyFor(url, userConfigEntries(), environment())
}

/**
 * Leaf's own environment, with the login shell's changes on top. Leaf's alone when the shell adds nothing, which keeps
 * Windows' variables matching without regard to case.
 */
private fun processEnvironment(shellVariables: Map<String, String>): Map<String, String> {
    val inherited = System.getenv()

    return if (shellVariables.isEmpty()) inherited else inherited + shellVariables
}

/**
 * The `http.proxy` and `http.<url>.proxy` entries of this config and the configs under it, with each key's last
 * value. JGit reads an entry with an empty value as having none, so an entry's name says that it's there.
 */
internal fun Config.proxyEntries(): List<ProxyConfigEntry> {
    fun entry(subsection: String?): ProxyConfigEntry? {
        val isSet = getNames(HTTP_SECTION, subsection, true).any { it.equals(PROXY_NAME, ignoreCase = true) }

        return if (isSet) ProxyConfigEntry(subsection, getString(HTTP_SECTION, subsection, PROXY_NAME).orEmpty()) else null
    }

    return listOfNotNull(entry(null)) + getSubsections(HTTP_SECTION).mapNotNull { entry(it) }
}

/**
 * The proxy entries of the user's and the system's config, in the order git reads them, from `git config --list`, which
 * also follows `includeIf`; from JGit's user config when git can't run.
 */
private suspend fun userProxyEntries(gitCli: GitCli): List<ProxyConfigEntry> {
    // A git dir that isn't a repository, so that git reads only the user's and the system's config
    val gitDir = File(System.getProperty("java.io.tmpdir"), "leaf-no-repository")
    val args = listOf("--git-dir=${gitDir.absolutePath}", "config", "--list", "-z")

    return when (val result = gitCli.run(File(System.getProperty("user.home")), args, CONFIG_TIMEOUT)) {
        is Either.Ok -> proxyEntriesOf(parseConfigList(result.value).map { it.key to it.value })
        is Either.Err -> {
            printError(TAG, "Reading the proxy settings with JGit, as git couldn't list them: ${result.error}")
            jgitUserConfig().proxyEntries()
        }
    }
}

/** The proxy entries among `git config --list`'s [entries], keys as git prints them: section and name in lower case. */
internal fun proxyEntriesOf(entries: List<Pair<String, String?>>): List<ProxyConfigEntry> = entries.mapNotNull { (key, value) ->
    when {
        key == "$HTTP_SECTION.$PROXY_NAME" -> ProxyConfigEntry(null, value.orEmpty())
        key.startsWith("$HTTP_SECTION.") && key.endsWith(".$PROXY_NAME") &&
            key.length > HTTP_SECTION.length + PROXY_NAME.length + 2 -> ProxyConfigEntry(
            key.substring(HTTP_SECTION.length + 1, key.length - PROXY_NAME.length - 1),
            value.orEmpty(),
        )

        else -> null
    }
}

private fun jgitUserConfig(): Config = try {
    SystemReader.getInstance().userConfig
} catch (e: IOException) {
    printError(TAG, "Could not read the user's git config: ${e.message}")
    Config()
} catch (e: ConfigInvalidException) {
    printError(TAG, "Could not read the user's git config: ${e.message}")
    Config()
}
