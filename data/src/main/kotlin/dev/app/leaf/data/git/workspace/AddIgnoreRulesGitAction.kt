// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.config.IgnorePlaceConfig
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.interfaces.IAddIgnoreRulesGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

private const val TAG = "AddIgnoreRulesGitAction"

class AddIgnoreRulesGitAction @Inject constructor(
    private val jgit: JGit,
    private val targets: IgnoreTargets,
) : IAddIgnoreRulesGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        place: IgnorePlace,
        rules: List<String>,
    ) = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            val target = targets.resolve(git.repository, place)

            target.problem?.let { raiseError(it) }

            try {
                IgnoreFiles.append(target, rules)
            } catch (e: IOException) {
                raiseError(GenericError("Couldn't write to ${target.file.path}", e))
            }

            // The rules are written, so a failure to remember the place isn't worth failing for
            try {
                IgnorePlaceConfig.save(git.repository, place)
            } catch (e: IOException) {
                printError(TAG, "Couldn't remember the place of the ignore rules", e)
            }
        }
    }
}
