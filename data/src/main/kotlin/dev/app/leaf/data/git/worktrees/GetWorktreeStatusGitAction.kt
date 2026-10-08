// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.IGetWorktreeStatusGitAction
import dev.app.leaf.domain.models.WorktreeStatus
import java.io.File
import javax.inject.Inject

class GetWorktreeStatusGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetWorktreeStatusGitAction {
    override suspend operator fun invoke(worktreePath: String): Either<WorktreeStatus, GitError> {
        // Without rename detection, which only matters for showing files: a rename counts as a deletion and an addition
        val args = listOf("status", "--porcelain=v2", "--branch", "-z", "--no-renames")

        return gitCli.run(File(worktreePath), args).mapOk { parseWorktreeStatus(it) }
    }
}
