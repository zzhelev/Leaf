// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkEditError
import dev.app.leaf.domain.errors.raiseError
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * The working file at [path] (relative to the working tree) that editing in place reads and writes (fork-only,
 * `docs/fork/inline-editing.md`): a regular file in the working tree, not a link, a folder, or something missing, which
 * are [HunkEditError.NotAFile].
 */
internal fun EitherContext<GitError>.editableFile(repository: Repository, path: String): Path {
    val workTree = repository.workTree.canonicalFile
    val file = File(workTree, path)

    val isInWorkTree = file.canonicalFile.toPath().startsWith(workTree.toPath()) &&
            file.absoluteFile.toPath().normalize().startsWith(workTree.toPath())

    if (!isInWorkTree || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        raiseError(HunkEditError.NotAFile(path))
    }

    return file.toPath()
}

/** Writes [bytes] to a file next to [path] and moves it into [path]'s place, with [path]'s mode. */
internal fun writeInPlace(path: Path, bytes: ByteArray) {
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
