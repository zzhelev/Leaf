// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.OperationType
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private val CONFIG_TIMEOUT = 10.seconds

/** `git config --get`'s exit code for a key that isn't set. */
private const val KEY_NOT_FOUND = 1

/** The file in the working tree where a repository shares its LFS settings, which git-lfs reads after git's config. */
private const val LFS_CONFIG_FILE = ".lfsconfig"

/**
 * The settings that name a repository's LFS server, in the order that git-lfs 3.8 reads them: [lfsUrl], for every
 * remote, then the remote's [remoteLfsUrl], then the remote's URL, which the caller turns into a server. Each is read
 * from git's own config first (the repository's, the user's and the system's, `includeIf` included), then from
 * `.lfsconfig` in the working tree, so an `lfs.url` in `.lfsconfig` beats a `remote.<name>.lfsurl` in git's config.
 * Also the settings by which git-lfs picks the remote: [branchRemote], then [lfsDefaultRemote].
 *
 * An empty value counts as not set, as git-lfs's documentation has it ("Default blank (derive from clone URL)").
 * git-lfs 3.8 itself takes an empty value as the server, and is then left without one, or takes `origin`'s for another
 * remote.
 */
class GetLfsUrlSettingGitAction @Inject constructor(
    private val gitCli: GitCli,
) {
    /**
     * `lfs.url`, or null when neither git's config nor `.lfsconfig` sets it. For an upload, `lfs.pushurl` comes first,
     * so one in `.lfsconfig` beats an `lfs.url` in git's config.
     */
    suspend fun lfsUrl(repository: Repository, operation: OperationType = OperationType.DOWNLOAD): String? {
        val pushUrl = if (operation == OperationType.UPLOAD) setting(repository, "lfs", null, "pushurl") else null

        return pushUrl ?: setting(repository, "lfs", null, "url")
    }

    /**
     * `remote.<remoteName>.lfsurl`, which git-lfs reads only when [lfsUrl] is null. For an upload,
     * `remote.<remoteName>.lfspushurl` comes first, from git's config only: git-lfs ignores it in `.lfsconfig`.
     */
    suspend fun remoteLfsUrl(
        repository: Repository,
        remoteName: String,
        operation: OperationType = OperationType.DOWNLOAD,
    ): String? {
        val pushUrl = if (operation == OperationType.UPLOAD) {
            gitConfigValue(repository, "remote", remoteName, "lfspushurl")
        } else {
            null
        }

        return pushUrl ?: setting(repository, "remote", remoteName, "lfsurl")
    }

    /**
     * `branch.<branch>.remote`, the remote whose server git-lfs uses for the branch, from git's config only: git-lfs
     * ignores it in `.lfsconfig`. Without `branch.<branch>.merge` too, which git-lfs doesn't need.
     */
    suspend fun branchRemote(repository: Repository, branch: String): String? =
        gitConfigValue(repository, "branch", branch, "remote")

    /** `remote.lfsdefault`: the remote whose server git-lfs uses when the current branch has no upstream remote. */
    suspend fun lfsDefaultRemote(repository: Repository): String? = setting(repository, "remote", null, "lfsdefault")

    private suspend fun setting(repository: Repository, section: String, subsection: String?, name: String): String? =
        gitConfigValue(repository, section, subsection, name)
            ?: lfsConfigFileValue(repository, section, subsection, name)

    /**
     * The value in git's config, read with `git config` for `includeIf`. git's answer stands when it runs; JGit's
     * config is read only when git can't run, or can't read its config.
     */
    private suspend fun gitConfigValue(
        repository: Repository,
        section: String,
        subsection: String?,
        name: String,
    ): String? {
        val directory = if (repository.isBare) repository.directory else repository.workTree
        val key = listOfNotNull(section, subsection, name).joinToString(".")
        val args = listOf("--git-dir=${repository.directory.absolutePath}", "config", "--get", key)
        val output = gitCli.execute(directory, args, CONFIG_TIMEOUT).okOrNull()

        val value = when (output?.exitCode) {
            0 -> output.stdout.trimEnd('\n')
            // Not set
            KEY_NOT_FOUND -> null
            else -> repository.config.getString(section, subsection, name)
        }

        return value?.takeIf { it.isNotBlank() }
    }

    private fun lfsConfigFileValue(
        repository: Repository,
        section: String,
        subsection: String?,
        name: String,
    ): String? {
        if (repository.isBare) {
            return null
        }

        val config = FileBasedConfig(File(repository.workTree, LFS_CONFIG_FILE), repository.fs)
        config.load()

        return config.getString(section, subsection, name)?.takeIf { it.isNotBlank() }
    }
}
