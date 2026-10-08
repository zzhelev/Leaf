// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetAheadBehindGitAction
import dev.app.leaf.domain.models.AheadBehind
import java.io.File
import javax.inject.Inject

class GetAheadBehindGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetAheadBehindGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        target: String,
    ): Either<AheadBehind, GitError> = either {
        val output = gitCli.run(File(repositoryPath), listOf("rev-list", "--left-right", "--count", "$base...$target")).bind()

        when (val aheadBehind = parseLeftRightCount(output)) {
            null -> Either.Err(GenericError("Unexpected output of git rev-list: $output"))
            else -> Either.Ok(aheadBehind)
        }
    }
}
