// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.discards.DiscardRestore
import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.domain.discards.RestoreWay
import dev.app.leaf.domain.errors.DiscardRecordError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import javax.inject.Inject

/**
 * Undo of a discard (fork-only, upstream issue 39): puts back what [DiscardStore] kept, path by path, without writing
 * over what changed since. A file and its index entry that are as the discard left them go back as they were. A file
 * that changed since gets the discarded lines back in a 3-way merge (`git merge-file`) of the file, the discard's result
 * and the discarded version, when they don't overlap. Anything else stays as it is, in the record, for later.
 */
class DiscardRestorer @Inject constructor(
    private val gitCli: GitCli,
) {
    private class Evaluated(val entry: StoredPath, val way: RestoreWay, val merged: Path? = null)

    /** How each path of the record [id] would come back, without changing anything. */
    suspend fun EitherContext<GitError>.plan(repositoryPath: String, repository: Repository, id: String): DiscardRestorePlan {
        val folder = recordFolder(repository, id)
        val stored = readStored(folder) ?: raiseError(DiscardRecordError.NotFound)
        val index = if (stored.withIndex) repository.readDirCache() else null
        val evaluated = evaluate(repository, folder, stored, index)

        evaluated.forEach { it.merged?.let(Files::deleteIfExists) }

        return DiscardRestorePlan(
            record = stored.toRecord(id, repositoryPath),
            ways = evaluated.associate { it.entry.path to it.way },
        )
    }

    /**
     * Puts back the paths of the record [id] that can come back, and keeps the others in it. The record goes once none
     * is left. With nothing to put back, raises [DiscardRecordError.ChangedSince] and changes nothing.
     */
    suspend fun EitherContext<GitError>.restore(repositoryPath: String, repository: Repository, id: String): DiscardRestore {
        val folder = recordFolder(repository, id)
        val stored = readStored(folder) ?: raiseError(DiscardRecordError.NotFound)
        // Locked while the entries are compared and written, as a git command may change them meanwhile
        val index = if (stored.withIndex) repository.lockDirCache() else null

        try {
            val evaluated = evaluate(repository, folder, stored, index)
            val restorable = evaluated.filter { it.way != RestoreWay.CHANGED_SINCE }
            val left = evaluated.filter { it.way == RestoreWay.CHANGED_SINCE }

            if (restorable.isEmpty()) {
                raiseError(DiscardRecordError.ChangedSince(left.map { it.entry.path }))
            }

            val editor = index?.editor()
            val workTree = repository.workTree.toPath()

            for (item in restorable) {
                val file = workTree.resolve(item.entry.path)

                when (item.way) {
                    RestoreWay.AS_IT_WAS -> if (item.entry.touchesFile) putBack(file, item.entry, folder)
                    RestoreWay.MERGED -> writeMerged(file, item.merged!!, item.entry)
                    RestoreWay.ALREADY_BACK, RestoreWay.CHANGED_SINCE -> Unit
                }

                editor?.add(indexEdit(repository, folder, item.entry))
            }

            editor?.commit()

            if (left.isEmpty()) {
                deleteRecordFolder(folder)
            } else {
                writeStored(folder, stored.copy(entries = left.map { it.entry }))
            }

            return DiscardRestore(
                record = stored.toRecord(id, repositoryPath),
                restored = restorable.map { it.entry.path },
                left = left.map { it.entry.path },
            )
        } finally {
            index?.unlock()
        }
    }

    private suspend fun evaluate(
        repository: Repository,
        folder: File,
        stored: StoredDiscard,
        index: DirCache?,
    ): List<Evaluated> {
        val workTree = repository.workTree.toPath()

        return stored.entries.map { entry ->
            val file = workTree.resolve(entry.path)
            val kind = kindOf(file)
            val indexIsAfter = index == null || index.matches(entry.path, entry.indexAfter)
            val indexIsBefore = index == null || index.matches(entry.path, entry.indexBefore)
            // A file that the discard left alone, whose index entry it reset, may be anything now
            val fileIsAfter = !entry.touchesFile || file.isSameAs(entry.after, kind)
            val fileIsBefore = !entry.touchesFile || file.isSameAs(entry.before, kind)

            when {
                entry.touchesFile && !canWriteAt(workTree, entry.path) -> Evaluated(entry, RestoreWay.CHANGED_SINCE)
                indexIsAfter && fileIsAfter && hasCopy(folder, entry.before) -> Evaluated(entry, RestoreWay.AS_IT_WAS)
                indexIsBefore && fileIsBefore -> Evaluated(entry, RestoreWay.ALREADY_BACK)
                indexIsAfter && kind == FileKind.FILE -> when (val merged = merge(repository, file, folder, entry)) {
                    null -> Evaluated(entry, RestoreWay.CHANGED_SINCE)
                    else -> Evaluated(entry, RestoreWay.MERGED, merged)
                }

                else -> Evaluated(entry, RestoreWay.CHANGED_SINCE)
            }
        }
    }

    /**
     * The file now, merged with the discarded version, on the base of what the discard left: the discarded lines come
     * back, and what changed since stays. Null when they overlap, either version is binary, or a version isn't kept.
     */
    private suspend fun merge(repository: Repository, file: Path, folder: File, entry: StoredPath): Path? {
        val files = File(folder, FILES_FOLDER).toPath()
        val discarded = entry.before.copy?.takeIf { entry.before.kind == FileKind.FILE } ?: return null
        val base = entry.after.copy?.takeIf { entry.after.kind == FileKind.FILE } ?: return null
        val current = Files.createTempFile(files, "merge-", "")

        try {
            Files.copy(file, current, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(current)
            return null
        }

        val args = listOf(
            "merge-file",
            "-q",
            current.toString(),
            files.resolve(base).toString(),
            files.resolve(discarded).toString(),
        )

        // The number of conflicts, or a negative number (255) for binary files and errors
        val isClean = when (val result = gitCli.execute(repository.workTree, args)) {
            is Either.Ok -> result.value.exitCode == 0
            is Either.Err -> false
        }

        if (!isClean) {
            Files.deleteIfExists(current)
            return null
        }

        return current
    }

    private fun putBack(file: Path, entry: StoredPath, folder: File) {
        val files = File(folder, FILES_FOLDER).toPath()

        // What the discard left: a file or a link, never a folder
        Files.deleteIfExists(file)

        val copy = entry.before.copy ?: return

        Files.createDirectories(file.parent)
        Files.move(files.resolve(copy), file)
    }

    /**
     * Puts [merged] in [file]'s place, with [file]'s mode, merged too: the executable bit that [entry]'s discard changed
     * comes back. Had it changed again since, it would be the discarded one already.
     */
    private fun writeMerged(file: Path, merged: Path, entry: StoredPath) {
        val permissions = Files.getFileAttributeView(file, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
            ?.permissions()

        if (permissions != null) {
            val mode = when {
                entry.before.executable == entry.after.executable -> permissions
                entry.before.executable -> permissions + executableAsReadable(permissions)
                else -> permissions - EXECUTE_PERMISSIONS
            }

            Files.setPosixFilePermissions(merged, mode)
        }

        try {
            Files.move(merged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // The git dir is on another file system than the working tree
            Files.move(merged, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun indexEdit(repository: Repository, folder: File, entry: StoredPath): DirCacheEditor.PathEdit {
        val before = entry.indexBefore ?: return DirCacheEditor.DeletePath(entry.path)
        val mode = FileMode.fromBits(before.mode)
        val id = ObjectId.fromString(before.id)

        if (mode != FileMode.GITLINK && before.copy != null) {
            writeBlob(repository, File(File(folder, FILES_FOLDER), before.copy).toPath())
        }

        return object : DirCacheEditor.PathEdit(entry.path) {
            override fun apply(ent: DirCacheEntry) {
                ent.fileMode = mode
                ent.setObjectId(id)
                // Unknown, so that git and JGit compare the content rather than trust the size and the time
                ent.setLength(0)
                ent.setLastModified(Instant.EPOCH)
            }
        }
    }

    /**
     * Writes [copy] into the object database, as git's gc may have removed the blob once the index no longer had it.
     * git writes it: JGit keeps the loose objects that it has seen in a cache, which still had one that git's gc had
     * removed, and its inserter then wrote nothing (found by a test). Without git, JGit's inserter writes it.
     */
    private suspend fun writeBlob(repository: Repository, copy: Path) {
        val content = Files.readAllBytes(copy)
        val args = listOf("hash-object", "-w", "-t", "blob", "--stdin")
        val written = when (val result = gitCli.execute(repository.workTree, args, input = content)) {
            is Either.Ok -> result.value.exitCode == 0
            is Either.Err -> false
        }

        if (!written) {
            repository.newObjectInserter().use { inserter ->
                inserter.insert(Constants.OBJ_BLOB, content)
                inserter.flush()
            }
        }
    }

    private fun recordFolder(repository: Repository, id: String): File {
        val root = File(repository.directory, AppStorage.DISCARDED_FOLDER_NAME)

        return File(root, File(id).name)
    }

    private fun hasCopy(folder: File, before: StoredFile): Boolean =
        before.copy == null || Files.exists(File(File(folder, FILES_FOLDER), before.copy).toPath(), LinkOption.NOFOLLOW_LINKS)
}

private val EXECUTE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/** The execute permissions that `chmod +x` gives: for whoever can read the file. */
private fun executableAsReadable(permissions: Set<PosixFilePermission>): Set<PosixFilePermission> = buildSet {
    if (PosixFilePermission.OWNER_READ in permissions) add(PosixFilePermission.OWNER_EXECUTE)
    if (PosixFilePermission.GROUP_READ in permissions) add(PosixFilePermission.GROUP_EXECUTE)
    if (PosixFilePermission.OTHERS_READ in permissions) add(PosixFilePermission.OTHERS_EXECUTE)
}

/** Whether [path]'s index entry is [stored]: a path absent from the index when null. One with conflicts never is. */
private fun DirCache.matches(path: String, stored: StoredIndexEntry?): Boolean {
    if (findEntry(path) < 0) {
        return stored == null
    }

    val entry = stageZeroEntry(path) ?: return false

    return stored != null && stored.isSameAs(entry.toStored())
}

/**
 * Whether a file can be written at [path]: a path of the working tree, not in a `.git`, whose folders that exist are
 * folders inside the working tree, so that writing doesn't follow a link out of it.
 */
internal fun canWriteAt(workTree: Path, path: String): Boolean {
    val segments = path.split('/')

    if (segments.any { it.isEmpty() || it == "." || it == ".." || it.equals(Constants.DOT_GIT, ignoreCase = true) }) {
        return false
    }

    var folder = workTree.resolve(path).parent

    while (folder != null && !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
        folder = folder.parent
    }

    if (folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
        return false
    }

    return try {
        folder.toRealPath().startsWith(workTree.toRealPath())
    } catch (_: IOException) {
        false
    }
}
