// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetMergeToolGitAction
import javax.inject.Inject

/** The merge tool that [OpenMergeToolGitAction] would run, read as it reads it. */
class GetMergeToolGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IGetMergeToolGitAction {
    override suspend fun invoke(repositoryPath: String): Either<String?, GitError> = either {
        val workTree = jgit.provide(repositoryPath) { git -> git.repository.workTree }.bind()

        Either.Ok(readMergeToolConfig(gitCli, workTree).bind().tool)
    }
}
