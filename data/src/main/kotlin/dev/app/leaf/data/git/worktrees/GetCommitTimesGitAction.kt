// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitTimesGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

class GetCommitTimesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitTimesGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        commitIds: Collection<String>,
    ): Either<Map<String, Long>, GitError> = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            RevWalk(git.repository).use { walk ->
                commitIds.distinct().mapNotNull { commitId ->
                    val objectId = ObjectId.fromString(commitId)

                    try {
                        commitId to walk.parseCommit(objectId).commitTime * 1000L
                    } catch (e: MissingObjectException) {
                        null
                    }
                }.toMap()
            }
        }
    }
}
