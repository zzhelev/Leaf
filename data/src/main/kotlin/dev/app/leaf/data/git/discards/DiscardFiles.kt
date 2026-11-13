// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import kotlinx.serialization.Serializable
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectInserter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** What is at a path of the working tree, as a record keeps it. */
@Serializable
internal enum class FileKind { ABSENT, FILE, LINK, FOLDER }

/**
 * A path of the working tree, before or after a discard.
 *
 * @param id the blob id of the content, a link's target for a link, as git would store it unfiltered.
 * @param copy the name of its copy in the record's `files` folder: what the discard threw away, or, for a file that the
 * discard changed, what it left (the base of a merge when the file changed since).
 */
@Serializable
internal data class StoredFile(
    val kind: FileKind,
    val executable: Boolean = false,
    val id: String? = null,
    val copy: String? = null,
)

/** An index entry at stage 0, before or after a discard that resets the index. */
@Serializable
internal data class StoredIndexEntry(val mode: Int, val id: String, val copy: String? = null) {
    fun isSameAs(other: StoredIndexEntry?): Boolean = other != null && mode == other.mode && id == other.id
}

/** A path that a discard changed. [deleted] is a new file, or what "Delete file" deleted, moved into the record. */
@Serializable
internal data class StoredPath(
    val path: String,
    val deleted: Boolean = false,
    val before: StoredFile,
    val after: StoredFile,
    val indexBefore: StoredIndexEntry? = null,
    val indexAfter: StoredIndexEntry? = null,
) {
    /** Whether the discard changed the file, not only its index entry. */
    val touchesFile: Boolean get() = before != after
}

/**
 * The `record.json` of a discard.
 *
 * @param kind the name of a [DiscardKind][dev.app.leaf.domain.discards.DiscardKind].
 * @param withIndex whether the discard reset index entries, and the record has them: a null entry is then a path that
 * wasn't in the index.
 * @param size the bytes that its copies take, for the limit on what discards may take on disk.
 */
@Serializable
internal data class StoredDiscard(
    val version: Int,
    val time: Long,
    val kind: String,
    val withIndex: Boolean,
    val size: Long,
    val entries: List<StoredPath>,
)

private val blobIds = ObjectInserter.Formatter()

/** What is at [file], without following a link. */
internal fun kindOf(file: Path): FileKind {
    val attributes = try {
        Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return FileKind.ABSENT
    }

    return when {
        attributes.isSymbolicLink -> FileKind.LINK
        attributes.isDirectory -> FileKind.FOLDER
        else -> FileKind.FILE
    }
}

/** Whether [file] can be run by its owner. Without POSIX permissions (Windows), git doesn't track it either. */
internal fun isExecutable(file: Path): Boolean = try {
    Files.getFileAttributeView(file, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?.readAttributes()
        ?.permissions()
        ?.contains(PosixFilePermission.OWNER_EXECUTE) == true
} catch (_: IOException) {
    false
} catch (_: UnsupportedOperationException) {
    false
}

/** The blob id of [file]'s bytes, or of a link's target, as `git hash-object --no-filters` gives it. */
internal fun blobIdOf(file: Path, kind: FileKind): String? = try {
    when (kind) {
        FileKind.FILE -> Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            blobIds.idFor(Constants.OBJ_BLOB, Files.size(file), input).name
        }

        FileKind.LINK -> blobIds.idFor(Constants.OBJ_BLOB, Constants.encode(Files.readSymbolicLink(file).toString())).name
        FileKind.ABSENT, FileKind.FOLDER -> null
    }
} catch (_: IOException) {
    // Changed while it was read: it's not what was recorded
    null
}

/** Whether what is at [file] now is [stored]: the same kind, content and mode. A folder is never, as it isn't read. */
internal fun Path.isSameAs(stored: StoredFile, kind: FileKind = kindOf(this)): Boolean {
    if (kind != stored.kind) {
        return false
    }

    return when (kind) {
        FileKind.ABSENT -> true
        FileKind.FOLDER -> false
        FileKind.LINK -> stored.id != null && blobIdOf(this, kind) == stored.id
        FileKind.FILE -> stored.id != null && isExecutable(this) == stored.executable && blobIdOf(this, kind) == stored.id
    }
}

/** The stage-0 entry of [path], or null when the path isn't in the index, or has conflicts. */
internal fun DirCache.stageZeroEntry(path: String): DirCacheEntry? {
    val first = findEntry(path)

    if (first < 0) {
        return null
    }

    // A path's entries are sorted by stage: one with conflicts starts at stage 1
    return getEntry(first).takeIf { it.stage == DirCacheEntry.STAGE_0 }
}

internal fun DirCacheEntry.toStored(copy: String? = null) = StoredIndexEntry(rawMode, objectId.name, copy)
