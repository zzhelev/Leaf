// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.discards.DiscardStore
import dev.app.leaf.data.git.discards.keepingDiscarded
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.editing.EditedFile
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.editing.editedFile
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkEditError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IWriteHunkEditGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import javax.inject.Inject

/**
 * Done of a hunk edited in place (fork-only, `docs/fork/inline-editing.md`): the working file with the typed lines in
 * place of the hunk's new side, when it still has the lines that the diff showed ([editedFile]). The lines it replaces
 * are kept for Undo ([DiscardKind.EDIT]). The file is written next to itself and moved into place, keeping its mode.
 */
class WriteHunkEditGitAction @Inject constructor(
    private val jgit: JGit,
    private val discardStore: DiscardStore,
) : IWriteHunkEditGitAction {
    override suspend fun invoke(repositoryPath: String, edit: HunkEdit, typed: String): Either<EditedFile, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val workTree = repository.workTree.canonicalFile
            val file = File(workTree, edit.path)
            val path = file.toPath()

            val isInWorkTree = file.canonicalFile.toPath().startsWith(workTree.toPath()) &&
                    file.absoluteFile.toPath().normalize().startsWith(workTree.toPath())

            if (!isInWorkTree || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                raiseError(HunkEditError.NotAFile(edit.path))
            }

            val edited = try {
                withContext(Dispatchers.IO) { editedFile(Files.readAllBytes(path), edit, typed) }
            } catch (e: IOException) {
                raiseError(HunkEditError.WriteFailed(edit.path, e.message.orEmpty()))
            } ?: raiseError(HunkEditError.FileChanged(edit.path))

            keepingDiscarded(discardStore, repositoryPath, repository, DiscardKind.EDIT, listOf(edit.path)) {
                try {
                    withContext(Dispatchers.IO) { writeInPlace(path, edited.bytes) }
                } catch (e: IOException) {
                    raiseError(HunkEditError.WriteFailed(edit.path, e.message.orEmpty()))
                }
            }

            edited
        }
}

/** Writes [bytes] to a file next to [path] and moves it into [path]'s place, with [path]'s mode. */
private fun writeInPlace(path: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}.leaf-edit-", ".tmp")

    try {
        Files.write(temporary, bytes)

        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { view ->
            Files.setPosixFilePermissions(temporary, view.readAttributes().permissions())
        }

        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // A file system without atomic moves still replaces the file
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
