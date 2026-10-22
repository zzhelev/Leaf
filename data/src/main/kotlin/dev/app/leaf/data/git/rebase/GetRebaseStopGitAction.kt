// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.repository.rebaseEditStop
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitsOnHeadGitAction
import dev.app.leaf.domain.interfaces.IGetRebaseStopGitAction
import dev.app.leaf.domain.rebase.RebaseStop
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import javax.inject.Inject

/** Where git's merge backend and JGit keep a rebase's state, then git's apply backend. */
private val REBASE_DIRS = listOf("rebase-merge", "rebase-apply")

/**
 * Reads the commit that a rebase stopped at (fork-only): `REBASE_HEAD`, which git writes at each stop of both its
 * backends, or else `rebase-merge/stopped-sha`, which JGit writes, with the branch being rebased (`head-name`) and
 * whether it stopped to edit the commit (`rebase-merge/amend`, git 2.54 and JGit checked). Null outside a rebase.
 */
class GetRebaseStopGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetRebaseStopGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<RebaseStop?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val rebaseDir = REBASE_DIRS.map { File(repository.directory, it) }.firstOrNull { it.isDirectory }
                ?: return@provide null
            val stoppedId = File(repository.directory, "REBASE_HEAD").firstLine()
                ?: File(rebaseDir, "stopped-sha").firstLine()
            val stopped = stoppedId?.let { repository.resolve(it) }
                ?: return@provide null
            val subject = RevWalk(repository).use { it.parseCommit(stopped).shortMessage }
            val branch = File(rebaseDir, "head-name").firstLine()
                ?.takeIf { it.startsWith(Constants.R_HEADS) }
                ?.let { Repository.shortenRefName(it) }

            RebaseStop(stopped.name, subject, branch, isEditStop = repository.rebaseEditStop() != null)
        }
}

/** Tells which commits HEAD contains (fork-only), with JGit's walk, which reads nothing but the commits. */
class GetCommitsOnHeadGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitsOnHeadGitAction {
    override suspend operator fun invoke(repositoryPath: String, commits: List<String>): Either<Set<String>, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val head = repository.resolve(Constants.HEAD) ?: return@provide emptySet()

            RevWalk(repository).use { walk ->
                val headCommit = walk.parseCommit(head)

                commits.filter { hash ->
                    val commit = repository.resolve(hash)?.let { walk.parseCommit(it) } ?: return@filter false

                    walk.isMergedInto(commit, headCommit)
                }.toSet()
            }
        }
}

private fun File.firstLine(): String? = takeIf { it.isFile }?.readText()?.trim()?.lineSequence()?.firstOrNull()
    ?.takeIf { it.isNotEmpty() }
