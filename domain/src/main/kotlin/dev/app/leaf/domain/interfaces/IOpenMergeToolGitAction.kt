// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.MergeToolTurn

interface IOpenMergeToolGitAction {
    /**
     * Resolves the conflicts of [filePath] (relative to the working tree) in the user's merge tool, with
     * `git mergetool`, and waits for the tool to close: git stages the file when the tool resolved it. [turn] is the
     * file's place among several resolved one after another, which the processing screen shows.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        filePath: String,
        turn: MergeToolTurn? = null,
    ): Either<Unit, GitError>
}
