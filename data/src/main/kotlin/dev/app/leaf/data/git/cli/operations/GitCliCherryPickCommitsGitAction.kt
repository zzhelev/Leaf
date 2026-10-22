// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.domain.cherrypick.cherryPickArguments
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.CherryPickSequenceCommand
import dev.app.leaf.domain.interfaces.ICherryPickCommitsGitAction
import dev.app.leaf.domain.interfaces.ICherryPickSequenceGitAction
import javax.inject.Inject

/**
 * Cherry-picks several commits with git (fork-only), through [GitCliOperations.run] as Leaf's other cherry-picks: git
 * asks for a signing key's passphrase in Leaf's dialog, and the processing screen can cancel it. git stops at a
 * commit's conflicts and keeps the rest in its sequencer. There's no JGit fallback: JGit has no sequencer.
 */
class GitCliCherryPickCommitsGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : ICherryPickCommitsGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        commits: List<String>,
        noCommit: Boolean,
    ): Either<Boolean, GitError> {
        return operations.run(repositoryPath, cherryPickArguments(commits, noCommit), mayStop = true)
    }
}

/**
 * Continues, skips or aborts a cherry-pick of several commits that git stopped (fork-only). git's abort also removes the
 * `SQUASH_MSG` that Leaf wrote for a pick as one commit (`--no-commit`), as `git reset --merge` does (git 2.54), which
 * would otherwise make the next staged change look like a squash merge.
 */
class GitCliCherryPickSequenceGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : ICherryPickSequenceGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        command: CherryPickSequenceCommand,
    ): Either<Boolean, GitError> {
        val mayStop = command != CherryPickSequenceCommand.ABORT

        return operations.run(repositoryPath, listOf("cherry-pick", command.option), mayStop)
    }
}
