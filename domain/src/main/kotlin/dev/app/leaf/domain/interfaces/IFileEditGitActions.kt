// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.editing.FileEdit
import dev.app.leaf.domain.editing.FileForEdit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/** Reads the working file at [path] and its version in the index, for the whole file's editor (fork-only, B2). */
interface IReadFileForEditGitAction {
    suspend operator fun invoke(repositoryPath: String, path: String): Either<FileForEdit, GitError>
}

/**
 * Save of the whole file's editor (fork-only, B2): writes [typed] into [edit]'s file when it's still [FileEdit.bytes],
 * and returns the bytes written.
 */
interface IWriteFileEditGitAction {
    suspend operator fun invoke(repositoryPath: String, edit: FileEdit, typed: String): Either<ByteArray, GitError>
}
