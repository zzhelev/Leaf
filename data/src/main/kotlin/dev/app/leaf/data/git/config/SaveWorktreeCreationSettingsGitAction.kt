// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISaveWorktreeCreationSettingsGitAction
import dev.app.leaf.domain.models.WorktreeCreationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveWorktreeCreationSettingsGitAction @Inject constructor(
    private val jgit: JGit,
) : ISaveWorktreeCreationSettingsGitAction {
    override suspend operator fun invoke(repositoryPath: String, settings: WorktreeCreationSettings) =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                WorktreeCreationConfig.save(git.repository, settings)
            }
        }
}
