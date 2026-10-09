// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommonGitDirGitAction
import javax.inject.Inject

class GetCommonGitDirGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommonGitDirGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<String, GitError> =
        jgit.provide(repositoryPath) { git ->
            // JGit resolves a linked worktree's commondir file ("../..") to a plain path, as the watcher reports
            git.repository.commonDirectory.absolutePath
        }
}
