// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISaveWorktreeBaseBranchGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveWorktreeBaseBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ISaveWorktreeBaseBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: String?) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            WorktreeBaseBranchConfig.save(git.repository, branch)
        }
    }
}
