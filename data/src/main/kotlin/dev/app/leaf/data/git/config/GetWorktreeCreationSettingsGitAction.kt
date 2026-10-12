// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IGetWorktreeCreationSettingsGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetWorktreeCreationSettingsGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetWorktreeCreationSettingsGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            WorktreeCreationConfig.load(git.repository)
        }
    }
}
