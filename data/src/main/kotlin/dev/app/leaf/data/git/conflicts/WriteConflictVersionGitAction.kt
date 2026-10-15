// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IWriteConflictVersionGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/** Longest file name that the copy gets, as many file systems take 255 bytes. */
private const val MAX_FILE_NAME_LENGTH = 200

/** Copies a version of a conflicted file out of the repository, for "Open in the default app" on a choice card. */
class WriteConflictVersionGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
) : IWriteConflictVersionGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        objectId: String,
        fileName: String,
        folder: String,
    ): Either<String, GitError> = withContext(Dispatchers.IO) {
        partialClone.retryingMissing(repositoryPath) {
            jgit.provide(repositoryPath) { git ->
                writeConflictVersion(git.repository, ObjectId.fromString(objectId), fileName, File(folder)).path
            }
        }
    }
}

/**
 * Writes the blob [id] into [folder] as `<id>_<name>`, the name of [fileName] keeping its extension, so that the
 * system picks the app for its type. A Git LFS pointer whose object is in the repository's store is written as the
 * object. The copy is written next to its place and moved in, so that an app that has the last copy open never reads
 * half of one. It's deleted when Leaf quits.
 */
internal fun writeConflictVersion(repository: Repository, id: ObjectId, fileName: String, folder: File): File {
    val name = fileName.substringAfterLast('/').takeLast(MAX_FILE_NAME_LENGTH)
    val target = File(folder, "${id.name.take(20)}_$name")

    folder.mkdirs()

    repository.newObjectReader().use { reader ->
        val media = reader.lfsPointer(id)
            ?.let { Lfs(repository).getMediaFile(LongObjectId.fromString(it.oid)) }
            ?.takeIf { Files.isRegularFile(it) }
        val partial = Files.createTempFile(folder.toPath(), "leaf-version", ".part")

        try {
            Files.newOutputStream(partial).use { out ->
                if (media != null) {
                    Files.copy(media, out)
                } else {
                    reader.open(id, Constants.OBJ_BLOB).copyTo(out)
                }
            }

            Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    target.deleteOnExit()

    return target
}
