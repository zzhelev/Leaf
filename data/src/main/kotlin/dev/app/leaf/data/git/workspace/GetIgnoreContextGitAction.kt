// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.config.IgnorePlaceConfig
import dev.app.leaf.domain.ignore.IgnoreContext
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.interfaces.IGetIgnoreContextGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetIgnoreContextGitAction @Inject constructor(
    private val jgit: JGit,
    private val targets: IgnoreTargets,
) : IGetIgnoreContextGitAction {
    override suspend fun invoke(repositoryPath: String) = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            IgnoreContext(
                remembered = IgnorePlaceConfig.load(git.repository),
                files = IgnorePlace.entries.associateWith { IgnoreFiles.read(targets.resolve(git.repository, it)) },
            )
        }
    }
}
