// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.commandDirectory
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val LFS_VERSION_TIMEOUT = 10.seconds

/** How long an answer of `git lfs version` is kept: git-lfs gets installed or removed while Leaf runs, rarely. */
private val CHECK_AGAIN_AFTER = 1.minutes

private const val LFS_FILTER = "lfs"

/**
 * Whether git-lfs does a repository's LFS work, or Leaf's built-in client has to (fork-only). git-lfs does it when
 * git's config names its filter and `git lfs version` works: `git lfs install` writes the filter, and a user who
 * installed git-lfs without running it, or whose config came from another machine, has one without the other.
 */
@Singleton
class GitLfsSetup(
    private val gitCli: GitCli,
    private val now: () -> Long,
) {
    @Inject
    constructor(gitCli: GitCli) : this(gitCli, System::currentTimeMillis)

    private val lock = Mutex()

    @Volatile
    private var lastCheck: Check? = null

    /**
     * Whether JGit's checkouts and staging run git-lfs for [repository], as its config tells them to: `smudge` and
     * `clean` are set for the LFS filter, and git-lfs works. Otherwise Leaf's built-in filters must, or JGit writes
     * pointer files, stages the files' bytes, or fails to run git-lfs.
     */
    suspend fun jgitRunsGitLfs(repository: Repository): Boolean {
        return namesGitLfsFor(repository.config, "smudge", "clean") && gitLfsWorks(repository.commandDirectory())
    }

    /**
     * Whether git runs git-lfs as the LFS filter of the commands that Leaf runs in [workingDirectory], as its config
     * there tells it to (`includeIf` included): `process` is set for the LFS filter, or `smudge` and `clean`, and
     * git-lfs works. Otherwise Leaf's own filter must ([dev.app.leaf.data.git.lfs.filter.LfsFilter]). A config that git
     * can't read counts as none, and git then fails anyway.
     */
    suspend fun gitRunsGitLfs(workingDirectory: File): Boolean {
        return gitNamesGitLfs(workingDirectory) && gitLfsWorks(workingDirectory)
    }

    /**
     * Whether git's config in [workingDirectory] gives the LFS filter git-lfs's commands, whether git-lfs works or not:
     * `process`, or `smudge` and `clean`, none blank.
     */
    suspend fun gitNamesGitLfs(workingDirectory: File): Boolean {
        val output = gitCli.execute(workingDirectory, listOf("config", "-z", "--get-regexp", FILTER_COMMANDS))
            .okOrNull()
            // git exits with 1 when no key matches
            ?.takeIf { it.exitCode == 0 }
            ?: return false

        val commands = parseConfigEntries(output.stdout)

        return !commands["filter.lfs.process"].isNullOrBlank() ||
            (!commands["filter.lfs.smudge"].isNullOrBlank() && !commands["filter.lfs.clean"].isNullOrBlank())
    }

    /** Whether `git lfs version` works, asked again after [CHECK_AGAIN_AFTER]. */
    suspend fun gitLfsWorks(directory: File): Boolean {
        fresh()?.let { return it.works }

        return lock.withLock {
            fresh()?.works ?: run {
                val works = gitCli.run(directory, listOf("lfs", "version"), LFS_VERSION_TIMEOUT) is Either.Ok
                lastCheck = Check(works, now())
                works
            }
        }
    }

    /** Has the next [gitLfsWorks] ask git-lfs again, as after Leaf set it up. */
    fun forgetGitLfsCheck() {
        lastCheck = null
    }

    private fun fresh(): Check? = lastCheck?.takeIf { now() - it.at < CHECK_AGAIN_AFTER.inWholeMilliseconds }

    private class Check(val works: Boolean, val at: Long)
}

/** The keys of the LFS filter's commands, for `git config --get-regexp`, whose patterns are POSIX extended ones. */
private const val FILTER_COMMANDS = """^filter\.lfs\.(process|smudge|clean)$"""

/**
 * `git config -z --get-regexp`'s [output] by key, the last value of each: entries end with a NUL, and a key without a
 * value (which git reads as true) has no line feed after it.
 */
internal fun parseConfigEntries(output: String): Map<String, String?> = output.split('\u0000')
    .filter { it.isNotEmpty() }
    .associate { entry ->
        if ('\n' in entry) entry.substringBefore('\n') to entry.substringAfter('\n') else entry to null
    }

/** Whether [config] gives the LFS filter each of the commands [keys] (`smudge`, `clean`, `process`), none blank. */
internal fun namesGitLfsFor(config: Config, vararg keys: String): Boolean = keys.all { key ->
    !config.getString(ConfigConstants.CONFIG_FILTER_SECTION, LFS_FILTER, key).isNullOrBlank()
}
