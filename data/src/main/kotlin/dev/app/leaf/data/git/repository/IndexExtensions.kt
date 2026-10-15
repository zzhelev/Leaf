// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.index.UnsupportedIndex
import org.eclipse.jgit.errors.CorruptObjectException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/** `DIRC`, which starts every index file. */
private const val INDEX_SIGNATURE = 0x44495243

/** An object id's length: JGit 7.7 doesn't open repositories with SHA-256 ids. */
private const val ID_LENGTH = 20

/** ctime, mtime (seconds and nanoseconds each), dev, ino, mode, uid, gid and size: ten 32-bit numbers. */
private const val STAT_LENGTH = 40

/** An entry's flag for a second, 16-bit, set of flags after the first (version 3 and later). */
private const val EXTENDED_FLAG = 0x4000

/** The split index's extension, which names the shared index that the index file holds the changes to. */
private const val SPLIT_INDEX_EXTENSION = "link"

/** The sparse index's extension, which says that entries can be folders. */
private const val SPARSE_INDEX_EXTENSION = "sdir"

/**
 * The signatures of the extensions in the index file [indexFile], in order, as gitformat-index describes them: they
 * come after the entries, so every entry is read past. Empty when there is no file, or it isn't an index that this
 * reads to the end.
 */
fun indexExtensions(indexFile: File): List<String> {
    if (!indexFile.isFile) {
        return emptyList()
    }

    return try {
        DataInputStream(BufferedInputStream(indexFile.inputStream(), 64 * 1024)).use { input ->
            IndexReader(input, indexFile.length()).extensions()
        }
    } catch (e: IOException) {
        // Cut short, or damaged: JGit reports it as it reads the index
        emptyList()
    }
}

/**
 * How git wrote [repository]'s index that JGit can't read, or null when JGit reads it: an extension that readers must
 * understand, as its signature doesn't start with an uppercase letter. JGit skips the others.
 */
fun unsupportedIndex(repository: Repository): UnsupportedIndex? {
    if (repository.isBare) {
        return null
    }

    val extensions = indexExtensions(repository.indexFile)
    val workTree = repository.workTree.absolutePath

    return when {
        SPLIT_INDEX_EXTENSION in extensions -> UnsupportedIndex.Split(
            workTree = workTree,
            splitIndexConfigured = repository.config.getBoolean(
                ConfigConstants.CONFIG_CORE_SECTION,
                null,
                "splitIndex",
                false,
            ),
        )

        SPARSE_INDEX_EXTENSION in extensions -> UnsupportedIndex.Sparse(workTree)
        else -> extensions.firstOrNull { it.first() !in 'A'..'Z' }?.let { UnsupportedIndex.UnknownExtension(workTree, it) }
    }
}

/**
 * This exception as the index that JGit couldn't read, when it is one: JGit reports an entry that it can't read as a
 * corrupt object ("Invalid path"). The index is read only then, so an index that JGit reads costs nothing.
 */
fun Throwable.unsupportedIndexError(repository: Repository?): UnsupportedIndexError? {
    if (repository == null || generateSequence(this) { it.cause }.none { it is CorruptObjectException }) {
        return null
    }

    return unsupportedIndex(repository)?.let(::UnsupportedIndexError)
}

private class IndexReader(private val input: DataInputStream, private val length: Long) {
    private var position = 0L

    fun extensions(): List<String> {
        if (readInt() != INDEX_SIGNATURE) {
            return emptyList()
        }

        val version = readInt()

        if (version !in 2..4) {
            return emptyList()
        }

        val entries = readInt().toLong() and 0xFFFFFFFFL

        for (entry in 0 until entries) {
            skipEntry(version)
        }

        val extensions = mutableListOf<String>()
        val end = length - ID_LENGTH

        while (position + 8 <= end) {
            val signature = ByteArray(4).also { read(it) }
            val size = readInt().toLong() and 0xFFFFFFFFL

            if (position + size > end) {
                throw IOException("The extension ends after the index")
            }

            extensions += String(signature, Charsets.US_ASCII)
            skip(size)
        }

        return extensions
    }

    private fun skipEntry(version: Int) {
        skip((STAT_LENGTH + ID_LENGTH).toLong())
        val flags = readUnsignedShort()
        var fixedLength = STAT_LENGTH + ID_LENGTH + 2

        if (version >= 3 && flags and EXTENDED_FLAG != 0) {
            skip(2)
            fixedLength += 2
        }

        if (version >= 4) {
            // How much of the previous entry's path this one starts with (a varint), then the rest of the path
            while (readUnsignedByte() and 0x80 != 0) {
                // The varint goes on
            }

            skipPath()
        } else {
            val pathLength = skipPath()
            // The path ends with 1 to 8 NUL bytes, which make the entry a multiple of 8 bytes long
            val entryLength = (fixedLength + pathLength + 8) and 7.inv()

            skip((entryLength - fixedLength - pathLength - 1).toLong())
        }
    }

    /** Skips a path up to its NUL, and returns its length without it. */
    private fun skipPath(): Int {
        var pathLength = 0

        while (readUnsignedByte() != 0) {
            pathLength++
        }

        return pathLength
    }

    private fun readInt(): Int = input.readInt().also { position += 4 }

    private fun readUnsignedShort(): Int = input.readUnsignedShort().also { position += 2 }

    private fun readUnsignedByte(): Int = input.readUnsignedByte().also { position += 1 }

    private fun read(bytes: ByteArray) {
        input.readFully(bytes)
        position += bytes.size
    }

    private fun skip(count: Long) {
        input.skipNBytes(count)
        position += count
    }
}
