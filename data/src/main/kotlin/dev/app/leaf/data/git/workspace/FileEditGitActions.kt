// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.data.git.discards.keepingDiscarded
import dev.app.leaf.data.git.lfs.parseLfsPointer
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.editing.FileEdit
import dev.app.leaf.domain.editing.FileForEdit
import dev.app.leaf.domain.editing.MAX_FILE_EDIT_LINES
import dev.app.leaf.domain.editing.fileEditOf
import dev.app.leaf.domain.editing.isUtf8
import dev.app.leaf.domain.editing.savedBytes
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkEditError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IReadFileForEditGitAction
import dev.app.leaf.domain.interfaces.IWriteFileEditGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject

/**
 * The working file for the whole file's editor (fork-only, `docs/fork/inline-editing.md`, B2), and its version in the
 * index, which the Unstaged diff compares it with, as text in the working file's charset. A file that the index doesn't
 * have (untracked, or added with `git add -N`, whose blob is empty) compares with nothing: every line is added. An LFS
 * pointer in the index has nothing to compare with.
 */
class ReadFileForEditGitAction @Inject constructor(
    private val jgit: JGit,
) : IReadFileForEditGitAction {
    override suspend fun invoke(repositoryPath: String, path: String): Either<FileForEdit, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val file = editableFile(repository, path)

            val bytes = try {
                withContext(Dispatchers.IO) { Files.readAllBytes(file) }
            } catch (e: IOException) {
                raiseError(HunkEditError.WriteFailed(path, e.message.orEmpty()))
            }

            val indexBytes = withContext(Dispatchers.IO) {
                repository.readDirCache().getEntry(path)?.let { entry ->
                    repository.open(entry.objectId, Constants.OBJ_BLOB).bytes
                }
            } ?: ByteArray(0)

            if (fileEditOf(path, bytes) == null) {
                raiseError(HunkEditError.TooLong(path, FileEdit(path, bytes, Charsets.ISO_8859_1).lineCount, MAX_FILE_EDIT_LINES))
            }

            val charset = if (bytes.isUtf8()) Charsets.UTF_8 else Charsets.ISO_8859_1
            val isLfsPointer = parseLfsPointer(indexBytes) != null && parseLfsPointer(bytes) == null

            FileForEdit(bytes, indexText = if (isLfsPointer) null else FileEdit(path, indexBytes, charset).text)
        }
}

/**
 * Save of the whole file's editor (fork-only, B2): the working file must still be the bytes that editing started from,
 * or last saved, else [HunkEditError.FileChanged] and nothing is written. The bytes that it replaces are kept for Undo
 * ([DiscardKind.EDIT]), and the file is written next to itself and moved into place, keeping its mode.
 */
class WriteFileEditGitAction @Inject constructor(
    private val jgit: JGit,
    private val discardStore: DiscardStore,
) : IWriteFileEditGitAction {
    override suspend fun invoke(repositoryPath: String, edit: FileEdit, typed: String): Either<ByteArray, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val path = editableFile(repository, edit.path)

            val now = try {
                withContext(Dispatchers.IO) { Files.readAllBytes(path) }
            } catch (e: IOException) {
                raiseError(HunkEditError.WriteFailed(edit.path, e.message.orEmpty()))
            }

            if (!now.contentEquals(edit.bytes)) raiseError(HunkEditError.FileChanged(edit.path))

            val bytes = savedBytes(edit, typed)

            keepingDiscarded(discardStore, repositoryPath, repository, DiscardKind.EDIT, listOf(edit.path)) {
                try {
                    withContext(Dispatchers.IO) { writeInPlace(path, bytes) }
                } catch (e: IOException) {
                    raiseError(HunkEditError.WriteFailed(edit.path, e.message.orEmpty()))
                }
            }

            bytes
        }
}
