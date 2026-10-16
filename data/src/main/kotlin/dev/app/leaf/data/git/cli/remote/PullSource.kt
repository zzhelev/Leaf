// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import java.io.File

/** The remote of a branch whose upstream is a local branch. */
internal const val LOCAL_REMOTE = "."

/** What a pull pulls, and where git runs (fork-only). */
internal class PullSource(
    val remote: String,
    /** The full name of the ref to pull from the remote, such as `refs/heads/main`. */
    val ref: String,
    /** Whether it's the branch's configured upstream. */
    val isUpstream: Boolean,
    val directory: File,
    val gitDir: File,
)

/**
 * What a pull pulls, as JGit's `PullCommand` (and git) pulls it: [remoteBranch] when one is given, or else the
 * branch's upstream (`branch.<name>.remote` and `branch.<name>.merge`), or else the branch of the same name on
 * `origin`. Refuses while a merge, rebase, cherry-pick or revert is in progress, and on a detached HEAD.
 */
internal fun pullSource(repository: Repository, remoteBranch: Branch?): PullSource {
    if (repository.repositoryState != RepositoryState.SAFE) {
        throw Exception("Can't pull while this is in progress: ${repository.repositoryState.description}")
    }

    val directory = repository.commandDirectory()
    val gitDir = repository.directory

    if (remoteBranch != null) {
        return PullSource(
            remote = remoteBranch.remoteName,
            ref = Constants.R_HEADS + remoteBranch.simpleName,
            isUpstream = false,
            directory = directory,
            gitDir = gitDir,
        )
    }

    val branch = repository.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
        ?: throw Exception("HEAD isn't on a branch, so there is no branch to pull into.")

    val config = repository.config
    val merge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE)
    val remote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE)

    return PullSource(
        remote = remote ?: Constants.DEFAULT_REMOTE_NAME,
        ref = (merge ?: branch).let { if (it.startsWith(Constants.R_REFS)) it else Constants.R_HEADS + it },
        isUpstream = merge != null && remote != null,
        directory = directory,
        gitDir = gitDir,
    )
}
