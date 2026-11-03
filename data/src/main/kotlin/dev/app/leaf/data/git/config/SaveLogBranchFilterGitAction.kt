// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISaveLogBranchFilterGitAction
import dev.app.leaf.domain.log.LogBranchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveLogBranchFilterGitAction @Inject constructor(
    private val jgit: JGit,
) : ISaveLogBranchFilterGitAction {
    override suspend operator fun invoke(repositoryPath: String, filter: LogBranchFilter) =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                LogBranchFilterConfig.save(git.repository, filter)
            }
        }
}
