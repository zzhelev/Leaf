// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISaveRefFolderExpansionGitAction
import dev.app.leaf.domain.sorting.RefFolderExpansion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveRefFolderExpansionGitAction @Inject constructor(
    private val jgit: JGit,
) : ISaveRefFolderExpansionGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        expansion: RefFolderExpansion,
    ) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            RefFolderExpansionConfig.save(git.repository, expansion)
        }
    }
}
