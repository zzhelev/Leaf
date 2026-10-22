// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cherrypick

import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.cherrypick.cherryPickSequenceOf
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.interfaces.IGetCherryPickSequenceGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Where git keeps a cherry-pick or revert of several commits, in the worktree's own git dir. */
private const val SEQUENCER = "sequencer"

/**
 * Reads the cherry-pick of several commits that git stopped in a worktree (fork-only), from `<git dir>/sequencer`:
 * `todo`, the commits still to pick, and `opts`, the options it started with. JGit doesn't know the sequencer, and
 * reads the repository as cherry-picking only while `CHERRY_PICK_HEAD` is there.
 */
class GetCherryPickSequenceGitAction @Inject constructor() : IGetCherryPickSequenceGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<CherryPickSequence?, GitError> =
        withContext(Dispatchers.IO) {
            val sequencer = File(repositoryPath, SEQUENCER)
            val todo = File(sequencer, "todo")

            try {
                if (!todo.isFile) {
                    Either.Ok(null)
                } else {
                    val opts = File(sequencer, "opts").takeIf { it.isFile }?.readText()

                    Either.Ok(cherryPickSequenceOf(todo.readText(), opts))
                }
            } catch (e: IOException) {
                Either.Err(GenericError("Couldn't read git's sequencer in $repositoryPath: ${e.message}"))
            }
        }
}
