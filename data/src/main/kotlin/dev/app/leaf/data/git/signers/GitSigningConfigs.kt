// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.credentials.ConfigEntry
import dev.app.leaf.data.git.credentials.parseConfigList
import dev.app.leaf.domain.errors.Either
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.Repository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitSigningConfigs"
private val CONFIG_TIMEOUT = 10.seconds

/** The sections whose files `git config --list` has already read in place of their entries. */
private val INCLUDE_SECTIONS = setOf("include", "includeif")

/**
 * JGit's signing settings, read from the config that git reads. That has the files that `includeIf` names, which JGit
 * skips, so `commit.gpgSign`, `tag.gpgSign`, `user.signingKey`, `gpg.format` and `gpg.program` set there apply too.
 * [config] has every entry, for the settings that [GpgConfig] doesn't keep, such as `gpg.ssh.program`.
 */
class GitGpgConfig(val config: Config) : GpgConfig(config)

/**
 * Reads the signing settings for Leaf's commits and tags from `git config --list -z`, as JGit reads no `includeIf`.
 * The commits that JGit makes itself (merges, rebases, cherry-picks and reverts) still sign as JGit's config says.
 */
class GitSigningConfigs @Inject constructor(
    private val gitCli: GitCli,
) {
    /** The settings in the repository whose git dir is [gitDir], or null if git couldn't list its config. */
    suspend fun of(gitDir: File): GitGpgConfig? {
        val args = listOf("--git-dir=${gitDir.absolutePath}", "config", "--list", "-z")

        val output = when (val result = gitCli.run(gitDir, args, CONFIG_TIMEOUT)) {
            is Either.Ok -> result.value
            is Either.Err -> {
                printError(TAG, "Signing as JGit's config says, as git couldn't list the config: ${result.error}")
                return null
            }
        }

        return try {
            GitGpgConfig(configOf(parseConfigList(output)))
        } catch (e: ConfigInvalidException) {
            printError(TAG, "Signing as JGit's config says, as git's config couldn't be read: ${e.message}")
            null
        }
    }
}

/**
 * A JGit config with [entries], as `git config --list` gives them, with their includes in place. They're written as
 * config text for JGit to read, so that a key without `=` stays one, which JGit reads as true, as git does.
 */
internal fun configOf(entries: List<ConfigEntry>): Config {
    val text = buildString {
        for ((key, value) in entries) {
            val firstDot = key.indexOf('.')
            val lastDot = key.lastIndexOf('.')

            if (firstDot <= 0 || lastDot == key.length - 1) {
                continue
            }

            val section = key.substring(0, firstDot)

            if (section.lowercase() in INCLUDE_SECTIONS) {
                continue
            }

            append('[').append(section)

            if (lastDot > firstDot) {
                append(" \"").append(escapeSubsection(key.substring(firstDot + 1, lastDot))).append('"')
            }

            append("]\n\t").append(key.substring(lastDot + 1))

            if (value != null) {
                append(" = \"").append(escapeValue(value)).append('"')
            }

            append('\n')
        }
    }

    return Config().apply { fromText(text) }
}

private fun escapeSubsection(subsection: String) = subsection
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

/** Inside double quotes, JGit keeps every other character as it is, tabs and backspaces included. */
private fun escapeValue(value: String) = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")

/** The config to read SSH signing settings from: git's, when Leaf passed it to JGit's command, or else JGit's. */
internal fun GpgConfig?.configOr(repository: Repository?): Config? =
    (this as? GitGpgConfig)?.config ?: repository?.config
