// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

class RunGitCommandGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IRunGitCommandGitAction {
    // No time limit: commands such as `git worktree add` run hooks and LFS filters, which can take long
    override suspend operator fun invoke(folder: String, args: List<String>): Either<String, GitError> {
        return gitCli.run(File(folder), args, timeout = Duration.INFINITE)
    }
}
