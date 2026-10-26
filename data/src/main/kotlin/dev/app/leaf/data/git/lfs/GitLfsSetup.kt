// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.commandDirectory
import dev.app.leaf.domain.errors.Either
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

    private fun fresh(): Check? = lastCheck?.takeIf { now() - it.at < CHECK_AGAIN_AFTER.inWholeMilliseconds }

    private class Check(val works: Boolean, val at: Long)
}

/** Whether [config] gives the LFS filter each of the commands [keys] (`smudge`, `clean`, `process`), none blank. */
internal fun namesGitLfsFor(config: Config, vararg keys: String): Boolean = keys.all { key ->
    !config.getString(ConfigConstants.CONFIG_FILTER_SECTION, LFS_FILTER, key).isNullOrBlank()
}
