// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import dev.app.leaf.common.printError
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.discards.DISCARD_KEPT_DAYS
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotices
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.MAX_DISCARDED_BYTES
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.random.Random

private const val TAG = "DiscardStore"

internal const val RECORD_FILE = "record.json"
internal const val FILES_FOLDER = "files"
private const val RECORD_VERSION = 1

/**
 * A file that a discard changed keeps a copy of what the discard left too, up to this size: Undo merges with it when
 * the file changed since. A bigger file that changed since stays as it is.
 */
internal const val MAX_MERGE_BASE_SIZE = 8L * 1024 * 1024

/** A record's folder without `record.json` is a discard that never finished, once it's this old. */
private val UNFINISHED_AFTER_MS = TimeUnit.DAYS.toMillis(1)

internal val recordJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** Leaf couldn't keep a copy of [path], so the discard mustn't run. */
class NotKeptException(val path: String, cause: IOException) : IOException(cause.message ?: cause.toString(), cause)

/**
 * Keeps what discards throw away (fork-only, upstream issue 39), in [AppStorage.DISCARDED_FOLDER_NAME] in the
 * worktree's git dir, for [DISCARD_KEPT_DAYS] days: a folder per discard, with its `record.json` and, in `files`, copies
 * of the files as they were. A deleted file, or folder, is moved there, which costs a rename where the git dir is on
 * the same file system as the working tree.
 *
 * Nothing goes into git's object database or refs: the records don't show in `git log --all` or `git stash list`, and
 * git's gc doesn't remove them.
 */
class DiscardStore @Inject constructor(
    private val discardNotices: DiscardNotices,
) {
    /**
     * Copies [paths] of the working tree, and with [withIndex] their index entries and blobs, before a discard of
     * [kind] runs. The caller runs the discard, and [PendingDiscard.finish]es in a `finally`, as a discard that failed
     * half way changed files too. Throws [NotKeptException] when a copy fails: the discard mustn't run then.
     */
    fun begin(
        repositoryPath: String,
        repository: Repository,
        kind: DiscardKind,
        paths: Collection<String>,
        withIndex: Boolean = false,
    ): PendingDiscard {
        val root = File(repository.directory, AppStorage.DISCARDED_FOLDER_NAME)
        // Later than the discard before it, even in the same millisecond, so that the records sort as they were made
        val time = lastTime.updateAndGet { last -> maxOf(last + 1, System.currentTimeMillis()) }
        val folder = newRecordFolder(root, time)

        val pending = PendingDiscard(
            store = this,
            repositoryPath = repositoryPath,
            repository = repository,
            kind = kind,
            withIndex = withIndex,
            folder = folder,
            time = time,
        )

        try {
            pending.capture(paths.distinct())
        } catch (e: NotKeptException) {
            pending.abandon()
            throw e
        }

        return pending
    }

    /** The discards kept in [repository]'s worktree, newest first, after the expired ones went. */
    fun records(repositoryPath: String, repository: Repository): List<DiscardRecord> {
        val root = File(repository.directory, AppStorage.DISCARDED_FOLDER_NAME)
        prune(root, keep = null)

        return storedRecords(root).map { (folder, stored) -> stored.toRecord(folder.name, repositoryPath) }
    }

    private companion object {
        val lastTime = AtomicLong()
    }

    internal fun announce(record: DiscardRecord) {
        discardNotices.recorded(record)
    }

    private fun newRecordFolder(root: File, time: Long): File {
        Files.createDirectories(root.toPath())

        while (true) {
            // The time first, so that the folders sort as the records' times do
            val folder = File(root, String.format(Locale.ROOT, "%013d-%04x", time, Random.nextInt(0x10000)))

            if (folder.mkdir()) {
                return folder
            }
        }
    }

    /**
     * Removes the records older than [DISCARD_KEPT_DAYS] days, then the oldest while the records take more than
     * [MAX_DISCARDED_BYTES], but never [keep] or, without one, the newest. Unfinished folders go after a day.
     */
    internal fun prune(root: File, keep: String?, now: Long = System.currentTimeMillis()) {
        val folders = root.listFiles { file -> file.isDirectory }?.sortedByDescending { it.name } ?: return
        val oldest = now - TimeUnit.DAYS.toMillis(DISCARD_KEPT_DAYS.toLong())
        val kept = keep ?: folders.firstOrNull { File(it, RECORD_FILE).isFile }?.name
        var size = 0L

        for (folder in folders) {
            val stored = readStored(folder)

            val expired = when {
                folder.name == keep -> false
                stored == null -> folder.lastModified() < now - UNFINISHED_AFTER_MS
                stored.time < oldest -> true
                else -> folder.name != kept && size + stored.size > MAX_DISCARDED_BYTES
            }

            if (expired) {
                deleteRecordFolder(folder)
            } else {
                size += stored?.size ?: 0
            }
        }
    }

}

internal fun deleteRecordFolder(folder: File) {
    // JGit's delete doesn't follow links, which a moved folder may have
    FileUtils.delete(folder, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
}

/** The finished records under [root], newest first. */
internal fun storedRecords(root: File): List<Pair<File, StoredDiscard>> =
    root.listFiles { file -> file.isDirectory }
        .orEmpty()
        .sortedByDescending { it.name }
        .mapNotNull { folder -> readStored(folder)?.let { folder to it } }

internal fun readStored(folder: File): StoredDiscard? {
    val file = File(folder, RECORD_FILE)

    if (!file.isFile) {
        return null
    }

    return try {
        recordJson.decodeFromString(StoredDiscard.serializer(), file.readText())
            .takeIf { it.version == RECORD_VERSION && it.kind in DiscardKind.entries.map(DiscardKind::name) }
    } catch (e: Exception) {
        printError(TAG, "Couldn't read the discard record $file", e)
        null
    }
}

/** Writes [stored] as the record of [folder], whole or not at all. */
internal fun writeStored(folder: File, stored: StoredDiscard) {
    val file = File(folder, RECORD_FILE)
    val temporary = File(folder, "$RECORD_FILE.tmp")

    temporary.writeText(recordJson.encodeToString(StoredDiscard.serializer(), stored))
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

internal fun StoredDiscard.toRecord(id: String, repositoryPath: String) = DiscardRecord(
    id = id,
    gitDir = repositoryPath,
    time = time,
    kind = DiscardKind.valueOf(kind),
    paths = entries.map { it.path },
    deletedPaths = entries.filter { it.deleted }.map { it.path },
)

/**
 * A discard about to run, whose files were copied ([DiscardStore.begin]). [moveOut] deletes a path into the record, and
 * [finish] keeps the record of what changed.
 */
class PendingDiscard internal constructor(
    private val store: DiscardStore,
    private val repositoryPath: String,
    private val repository: Repository,
    private val kind: DiscardKind,
    private val withIndex: Boolean,
    private val folder: File,
    private val time: Long,
) {
    private class Before(val file: StoredFile, val index: StoredIndexEntry?, val isDeleted: Boolean)

    private val workTree: Path = repository.workTree.toPath()
    private val files = File(folder, FILES_FOLDER).toPath()
    private val befores = LinkedHashMap<String, Before>()
    private var copies = 0
    private var isDone = false

    internal fun capture(paths: List<String>) {
        val index = if (withIndex) repository.readDirCache() else null

        for (path in paths) {
            val file = workTree.resolve(path)

            try {
                val kind = kindOf(file)
                val copy = when (kind) {
                    // A folder (a submodule) isn't changed by a discard, nor copied
                    FileKind.ABSENT, FileKind.FOLDER -> null
                    FileKind.FILE, FileKind.LINK -> copyOf(file)
                }

                val indexEntry = index?.stageZeroEntry(path)?.let { entry ->
                    // A submodule's commit is in its own repository; a blob is copied, as git's gc may remove it once
                    // nothing in the index has it
                    val blobCopy = if (entry.fileMode == FileMode.GITLINK) null else copyOfBlob(entry.objectId)
                    entry.toStored(blobCopy)
                }

                befores[path] = Before(
                    file = StoredFile(
                        kind = kind,
                        executable = kind == FileKind.FILE && isExecutable(file),
                        id = copy?.let { blobIdOf(files.resolve(it), kind) },
                        copy = copy,
                    ),
                    index = indexEntry,
                    isDeleted = false,
                )
            } catch (e: IOException) {
                throw NotKeptException(path, e)
            }
        }
    }

    /**
     * Deletes [path], a file, a link or a folder, by moving it into the record. Returns false when nothing is there,
     * or when a folder can't be moved, as into a git dir on another file system: the caller deletes it then, and it
     * can't be undone. Throws when a file can't be moved, which leaves it where it is.
     */
    fun moveOut(path: String): Boolean {
        val file = workTree.resolve(path)
        val kind = kindOf(file)

        if (kind == FileKind.ABSENT) {
            return false
        }

        val executable = kind == FileKind.FILE && isExecutable(file)
        val name = nextCopyName("d")

        Files.createDirectories(files)

        try {
            Files.move(file, files.resolve(name))
        } catch (e: IOException) {
            if (kind == FileKind.FOLDER) {
                return false
            }

            throw e
        }

        val moved = files.resolve(name)
        befores[path] = Before(
            file = StoredFile(kind, executable, blobIdOf(moved, kind), name),
            index = befores[path]?.index,
            isDeleted = true,
        )

        return true
    }

    /**
     * Reads what the discard left and keeps the paths that it changed. Without any, the record goes. Otherwise the tab
     * is told ([DiscardStore.announce]), and older records go when they're expired or too much. Never throws: a
     * discard that ran is done, record or not.
     */
    fun finish(): DiscardRecord? {
        if (isDone) {
            return null
        }

        isDone = true

        return try {
            val index = if (withIndex) repository.readDirCache() else null
            val entries = befores.mapNotNull { (path, before) -> changeOf(path, before, index?.stageZeroEntry(path)?.toStored()) }

            if (entries.isEmpty()) {
                deleteRecordFolder(folder)
                return null
            }

            writeStored(
                folder,
                StoredDiscard(
                    version = RECORD_VERSION,
                    time = time,
                    kind = kind.name,
                    withIndex = withIndex,
                    size = if (Files.exists(files)) sizeOf(files) else 0,
                    entries = entries,
                ),
            )

            store.prune(folder.parentFile, keep = folder.name)

            DiscardRecord(
                id = folder.name,
                gitDir = repositoryPath,
                time = time,
                kind = kind,
                paths = entries.map { it.path },
                deletedPaths = entries.filter { it.deleted }.map { it.path },
            ).also(store::announce)
        } catch (e: Exception) {
            printError(TAG, "Couldn't keep the record of a discard in $folder", e)
            null
        }
    }

    /** A discard that didn't run: its copies go. */
    internal fun abandon() {
        isDone = true
        deleteRecordFolder(folder)
    }

    private fun changeOf(path: String, before: Before, indexAfter: StoredIndexEntry?): StoredPath? {
        val file = workTree.resolve(path)
        val kind = kindOf(file)
        // A folder that wasn't moved out is a submodule, which a discard leaves alone
        val fileChanged = if (before.file.kind == FileKind.FOLDER && !before.isDeleted) {
            kind != FileKind.FOLDER
        } else {
            !file.isSameAs(before.file, kind)
        }
        val indexChanged = withIndex && !(before.index?.isSameAs(indexAfter) ?: (indexAfter == null))

        if (!fileChanged) {
            before.file.copy?.let { Files.deleteIfExists(files.resolve(it)) }
        }

        if (!indexChanged) {
            before.index?.copy?.let { Files.deleteIfExists(files.resolve(it)) }
        }

        if (!fileChanged && !indexChanged) {
            return null
        }

        // Only the index entry changed: the file stays as it is, whatever happens to it since
        if (!fileChanged) {
            val untouched = before.file.copy(copy = null)

            return StoredPath(path, before = untouched, after = untouched, indexBefore = before.index, indexAfter = indexAfter)
        }

        // What the discard left, as the base of a merge with what the file became since
        val afterCopy = if (kind == FileKind.FILE && Files.size(file) <= MAX_MERGE_BASE_SIZE) copyOf(file) else null
        val after = StoredFile(
            kind = kind,
            executable = kind == FileKind.FILE && isExecutable(file),
            id = afterCopy?.let { blobIdOf(files.resolve(it), kind) } ?: blobIdOf(file, kind),
            copy = afterCopy,
        )

        return StoredPath(
            path = path,
            deleted = before.isDeleted,
            before = before.file,
            after = after,
            indexBefore = before.index,
            indexAfter = indexAfter,
        )
    }

    private fun copyOf(file: Path): String {
        val name = nextCopyName("c")

        Files.createDirectories(files)
        Files.copy(file, files.resolve(name), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)

        return name
    }

    private fun copyOfBlob(id: ObjectId): String {
        val name = nextCopyName("i")

        Files.createDirectories(files)
        Files.newOutputStream(files.resolve(name)).use { output ->
            repository.newObjectReader().use { reader -> reader.open(id, Constants.OBJ_BLOB).copyTo(output) }
        }

        return name
    }

    private fun nextCopyName(prefix: String) = "$prefix${copies++}"
}

/** The bytes under [path], a folder's files included, without following links. */
private fun sizeOf(path: Path): Long = Files.walk(path).use { paths ->
    paths.mapToLong { Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size() }.sum()
}
