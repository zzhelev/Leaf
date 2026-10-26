// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.common.printError
import org.eclipse.jgit.lfs.LfsBlobLoader
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val TAG = "LfsStore"

/** A Git LFS pointer is never larger than this, as git-lfs reads them. */
const val MAX_LFS_POINTER_SIZE = 1024

private const val BUFFER_SIZE = 64 * 1024

/** The clean filter keeps a file this large or smaller in memory, and writes a larger one to the store's tmp. */
private const val IN_MEMORY_LIMIT = 4 * 1024 * 1024

/**
 * Where a repository's Git LFS objects are, as git-lfs keeps them (fork-only): `<common git dir>/lfs/objects/aa/bb/<oid>`,
 * shared by every worktree of the repository, and `lfs/tmp` for files on their way in.
 *
 * JGit's `Lfs` takes the repository's own git dir, which for a linked worktree is `<common git dir>/worktrees/<name>`.
 * Leaf's built-in client used it, so it kept a linked worktree's objects where git-lfs and the other worktrees don't
 * look, and `git worktree remove` deleted them. [find] moves such an object here the first time it's needed.
 */
class LfsStore internal constructor(
    val root: Path,
    /** The linked worktree's own `lfs` folder, where an older Leaf put its objects, or null in the main worktree. */
    private val worktreeRoot: Path?,
) {
    val objects: Path get() = root.resolve("objects")

    val tmp: Path get() = root.resolve("tmp")

    /** Where the object [oid] goes, whether the store has it or not. */
    fun mediaFile(oid: AnyLongObjectId): Path = mediaFileIn(objects, oid)

    /**
     * The object [oid] when the store has it, with [size] bytes unless that's null, or else null. One that an older
     * Leaf put in a linked worktree's own git dir is moved here first.
     */
    fun find(oid: AnyLongObjectId, size: Long? = null): Path? {
        val media = mediaFile(oid)

        if (!Files.isRegularFile(media)) {
            moveFromWorktree(oid, media)
        }

        return media.takeIf { Files.isRegularFile(it) && (size == null || Files.size(it) == size) }
    }

    private fun moveFromWorktree(oid: AnyLongObjectId, media: Path) {
        val old = worktreeRoot?.let { mediaFileIn(it.resolve("objects"), oid) } ?: return

        if (!Files.isRegularFile(old)) {
            return
        }

        try {
            Files.createDirectories(media.parent)
            Files.move(old, media, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Another process may have moved it, or put the object in its place, first
            printError(TAG, "Could not move LFS object ${oid.name()} from the worktree's own git dir: ${e.message}")
        }
    }

    /**
     * Cleans [input] into [output] as git-lfs's clean filter does: an empty file stays empty, and a Git LFS pointer
     * stays as it is. Anything else goes into the store, and [output] gets its pointer. Neither stream is closed.
     */
    fun clean(input: InputStream, output: OutputStream) {
        val content = CleanedContent()

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break

                digest.update(buffer, 0, read)
                content.write(buffer, read)
            }

            content.finish()

            if (content.size == 0L) {
                return
            }

            if (content.size <= MAX_LFS_POINTER_SIZE) {
                val bytes = content.bytes()

                if (parseLfsPointer(bytes) != null) {
                    output.write(bytes)
                    return
                }
            }

            val oid = LongObjectId.fromRaw(digest.digest())
            val media = mediaFile(oid)

            // An object of another size isn't the object: a download or a copy broke off
            if (!Files.isRegularFile(media) || Files.size(media) != content.size) {
                Files.createDirectories(media.parent)
                Files.move(content.file(), media, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }

            output.write(lfsPointerText(oid, content.size).toByteArray())
        } finally {
            content.delete()
        }
    }

    /**
     * What [clean] reads: in memory up to [IN_MEMORY_LIMIT], in a file in [tmp] past it. JGit's status cleans a file
     * whose time changed each time it runs, as it doesn't refresh the index as git does, so a file written for each
     * small object would be written again and again; git-lfs writes one each time too, but git cleans only once.
     */
    private inner class CleanedContent {
        private val memory = ByteArrayOutputStream()
        private var temp: Path? = null
        private var file: OutputStream? = null

        var size = 0L
            private set

        fun write(buffer: ByteArray, count: Int) {
            size += count

            val stream = file ?: if (size <= IN_MEMORY_LIMIT) memory else spill()
            stream.write(buffer, 0, count)
        }

        private fun spill(): OutputStream {
            val stream = Files.newOutputStream(newTempFile())
            memory.writeTo(stream)
            memory.reset()
            file = stream

            return stream
        }

        fun finish() {
            file?.close()
            file = null
        }

        /** The content, when it's small enough to be in memory. */
        fun bytes(): ByteArray = memory.toByteArray()

        /** A file with the content, to move into place. */
        fun file(): Path = temp ?: newTempFile().also { Files.write(it, memory.toByteArray()) }

        private fun newTempFile(): Path {
            Files.createDirectories(tmp)

            return Files.createTempFile(tmp, "clean-", ".tmp").also { temp = it }
        }

        fun delete() {
            file?.close()
            temp?.let { Files.deleteIfExists(it) }
        }
    }
}

/** The LFS store of this repository and of every worktree of it ([LfsStore]). */
fun Repository.lfsStore(): LfsStore {
    val gitDir = directory.toPath().toAbsolutePath().normalize()
    val commonGitDir = (commonDirectory ?: directory).toPath().toAbsolutePath().normalize()

    return LfsStore(
        root = commonGitDir.resolve("lfs"),
        worktreeRoot = gitDir.resolve("lfs").takeIf { gitDir != commonGitDir },
    )
}

/**
 * [loader]'s content as Leaf shows and merges it: the object from the store for a Git LFS pointer whose object the
 * store has, and [loader] itself otherwise. It downloads nothing: diffs, blame and JGit's merges read through it, and
 * nothing downloads an LFS object without the user asking (decided 2026-10-15). JGit's own `LfsBlobFilter` downloaded
 * it with JGit's LFS client, outside Leaf's credentials and TLS checks, and looked in JGit's store.
 */
fun Repository.lfsContentOf(loader: ObjectLoader): ObjectLoader {
    if (loader.size > MAX_LFS_POINTER_SIZE) {
        return loader
    }

    // A working tree file's loader is large whatever its size, and has no cached bytes
    val head = loader.openStream().use { it.readNBytes(MAX_LFS_POINTER_SIZE + 1) }
    val pointer = parseLfsPointer(head, allowExtensions = false) ?: return loader
    val media = lfsStore().find(pointer.oid, pointer.size) ?: return loader

    return LfsBlobLoader(media)
}

/**
 * [bytes] as a Git LFS pointer, or null when they aren't one, read as git-lfs 3.8 reads pointers (`decodeKVData`):
 * `version`, `oid` and `size` in that order, one per line, blank lines and extension lines (`ext-0-name value`)
 * between them allowed, and nothing after `size`. JGit's `LfsPointer.parseLfsPointer` takes more: comment lines, and a
 * pointer that a merge's conflict markers broke, which it read as the side last in the file.
 *
 * A pointer with extension lines needs git-lfs's extension programs to turn its object into the file, which Leaf
 * doesn't run: a smudge filter passes [allowExtensions] false, and writes such a pointer as it is.
 */
fun parseLfsPointer(bytes: ByteArray, allowExtensions: Boolean = true): LfsPointer? {
    if (bytes.isEmpty() || bytes.size > MAX_LFS_POINTER_SIZE) {
        return null
    }

    val values = mutableMapOf<String, String>()
    var keysRead = 0

    // git-lfs trims white space around the pointer, but not inside it
    for (rawLine in bytes.decodeToString().trim().split('\n')) {
        // Go's line scanner drops a line's \r
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) continue

        val key = line.substringBefore(' ', missingDelimiterValue = "")
        val value = line.substringAfter(' ', missingDelimiterValue = "")

        if (key.isEmpty() || keysRead == POINTER_KEYS.size) {
            return null
        }

        if (key != POINTER_KEYS[keysRead]) {
            if (!allowExtensions || !EXTENSION_KEY.matches(key)) {
                return null
            }

            continue
        }

        values[key] = value
        keysRead++
    }

    if (values["version"] !in POINTER_VERSIONS) {
        return null
    }

    val oid = values["oid"]?.removePrefix("sha256:")?.takeIf { it != values["oid"] && OID.matches(it) } ?: return null
    val size = values["size"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null

    return LfsPointer(LongObjectId.fromString(oid), size)
}

/** The keys of a pointer, in the order that git-lfs wants them. */
private val POINTER_KEYS = listOf("version", "oid", "size")

/** The versions that git-lfs reads as its own (`v1Aliases`). */
private val POINTER_VERSIONS = setOf(
    "http://git-media.io/v/2",
    "https://hawser.github.com/spec/v1",
    "https://git-lfs.github.com/spec/v1",
)

private val EXTENSION_KEY = Regex("ext-\\d-\\w+.*")

private val OID = Regex("[0-9a-f]{64}")

/** The pointer to the object [oid] of [size] bytes, as git-lfs writes it. */
fun lfsPointerText(oid: AnyLongObjectId, size: Long): String {
    return "version https://git-lfs.github.com/spec/v1\noid sha256:${oid.name()}\nsize $size\n"
}

private fun mediaFileIn(objects: Path, oid: AnyLongObjectId): Path {
    val name = oid.name()

    return objects.resolve(name.substring(0, 2)).resolve(name.substring(2, 4)).resolve(name)
}
