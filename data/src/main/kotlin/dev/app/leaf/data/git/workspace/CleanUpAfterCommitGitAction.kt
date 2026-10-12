// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ICleanUpAfterCommitGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.util.FileUtils
import java.io.File
import javax.inject.Inject

/**
 * The files that git's commit removes from the git dir and JGit's leaves (git 2.54): the message of a squash merge, and
 * what a merge that stopped, or a squash merge with conflicts, wrote besides `MERGE_HEAD`.
 */
private val FILES_THAT_GITS_COMMIT_REMOVES = listOf("SQUASH_MSG", "MERGE_MSG", "MERGE_MODE", "AUTO_MERGE")

class CleanUpAfterCommitGitAction @Inject constructor(
    private val jgit: JGit,
) : ICleanUpAfterCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<Unit, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            // Once the commit is made, a merge, cherry-pick or revert is over. A rebase that goes on keeps its message
            // in MERGE_MSG, and Leaf's draft of it too.
            if (repository.repositoryState == RepositoryState.SAFE) {
                withContext(Dispatchers.IO) {
                    for (name in FILES_THAT_GITS_COMMIT_REMOVES) {
                        FileUtils.delete(File(repository.directory, name), FileUtils.SKIP_MISSING)
                    }
                }
            }
        }
}
