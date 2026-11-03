// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IGetLogBranchFilterGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetLogBranchFilterGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetLogBranchFilterGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            LogBranchFilterConfig.load(git.repository)
        }
    }
}
