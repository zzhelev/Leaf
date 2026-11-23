// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.editing.EditedFile
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/** Writes the lines [typed] in place of [edit]'s into the working file (fork-only, `docs/fork/inline-editing.md`). */
interface IWriteHunkEditGitAction {
    suspend operator fun invoke(repositoryPath: String, edit: HunkEdit, typed: String): Either<EditedFile, GitError>
}
