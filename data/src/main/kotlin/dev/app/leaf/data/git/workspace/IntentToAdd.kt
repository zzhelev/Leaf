// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Repository
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException

/*
 * Index entries of files added with `git add -N`, intent to add (fork-only). Such an entry has the path, the empty blob
 * and a flag. git takes it as a file that the index doesn't have yet (probed with git 2.54): `git status` lists it as new
 * in the working tree (` A`), `git diff` shows it whole and `git diff --cached` not at all, `git write-tree` and
 * `git commit` leave it out of the tree and keep it in the index, and `git add` stages it as a new file.
 *
 * JGit 7.7 reads the flag (`DirCacheEntry.isIntentToAdd`) and nothing in it uses it: its status lists such a file as added
 * and modified, its commit stores it as an empty file, and its add updates the entry it finds and keeps the flag.
 */

/** The flag is one of the index's extended flags, which versions 3 and 4 of its format have, and version 2 doesn't. */
private const val FIRST_VERSION_WITH_EXTENDED_FLAGS = 3

/** "DIRC", which every index file starts with. */
private const val INDEX_SIGNATURE = 0x44495243

/**
 * The paths of the index's intent-to-add entries. An index in version 2 of the format can't have one, so it isn't read
 * past its header: most repositories' indexes are in version 2.
 */
fun Repository.intentToAddPaths(): Set<String> {
    if (!mayHaveIntentToAdd()) {
        return emptySet()
    }

    val dirCache = readDirCache()

    return (0 until dirCache.entryCount)
        .map { dirCache.getEntry(it) }
        .filter { it.isIntentToAdd }
        .mapTo(mutableSetOf()) { it.pathString }
}

/**
 * Clears the flag of the intent-to-add entries that [isStaged] picks, which JGit's add has just staged: it updates the
 * entry it finds and keeps its flags, and leaves one whose file is still empty as it is, so the file stayed intent to add,
 * where `git add` stages it. Writes the index only when such an entry is there.
 */
fun Repository.stageIntentToAdd(isStaged: (path: String) -> Boolean) {
    if (!mayHaveIntentToAdd()) {
        return
    }

    val dirCache = lockDirCache()

    try {
        val entries = (0 until dirCache.entryCount).map { dirCache.getEntry(it) }

        if (entries.none { it.isIntentToAdd && isStaged(it.pathString) }) {
            return
        }

        val builder = dirCache.builder()

        for (entry in entries) {
            builder.add(if (entry.isIntentToAdd && isStaged(entry.pathString)) entry.withoutIntentToAdd() else entry)
        }

        if (!builder.commit()) {
            throw IOException("Couldn't write the index: ${indexFile.absolutePath}")
        }
    } finally {
        dirCache.unlock()
    }
}

/**
 * Drops the intent-to-add entries of [paths] from the index, after Leaf deleted their files, as `git rm -f` drops them.
 * Deleting the file alone would leave the entry, which git lists as deleted (` D`). Other entries stay. Writes the index
 * only when such an entry is there.
 */
fun Repository.forgetIntentToAdd(paths: Collection<String>) {
    if (paths.isEmpty() || !mayHaveIntentToAdd()) {
        return
    }

    val dirCache = lockDirCache()

    try {
        val entries = (0 until dirCache.entryCount).map { dirCache.getEntry(it) }
        val forgotten = entries.filter { it.isIntentToAdd && it.pathString in paths }

        if (forgotten.isEmpty()) {
            return
        }

        val builder = dirCache.builder()

        for (entry in entries) {
            if (entry !in forgotten) {
                builder.add(entry)
            }
        }

        if (!builder.commit()) {
            throw IOException("Couldn't write the index: ${indexFile.absolutePath}")
        }
    } finally {
        dirCache.unlock()
    }
}

/**
 * Takes the intent-to-add entries out of this locked index, in memory, for a tree written from it to leave them out, as
 * git's commit does. Writing the index afterwards would drop them from it: only for a caller that writes nothing back.
 */
internal fun DirCache.leaveIntentToAddOut() {
    val entries = (0 until entryCount).map { getEntry(it) }

    if (entries.none { it.isIntentToAdd }) {
        return
    }

    val builder = builder()

    for (entry in entries) {
        if (!entry.isIntentToAdd) {
            builder.add(entry)
        }
    }

    builder.finish()
}

/** The same entry without the flag, which JGit has no setter for. */
private fun DirCacheEntry.withoutIntentToAdd(): DirCacheEntry = DirCacheEntry(rawPath, stage).also {
    it.fileMode = fileMode
    it.setObjectId(objectId)
    it.setLength(length)
    it.setLastModified(lastModifiedInstant)
    it.creationTime = creationTime
    it.isAssumeValid = isAssumeValid
    it.isUpdateNeeded = isUpdateNeeded
}

/**
 * Whether the index's format can hold an intent-to-add entry, from its header. No index has none, and a file that
 * doesn't look like an index is left to JGit to refuse.
 */
private fun Repository.mayHaveIntentToAdd(): Boolean {
    if (isBare) {
        return false
    }

    return try {
        DataInputStream(indexFile.inputStream()).use { input ->
            input.readInt() != INDEX_SIGNATURE || input.readInt() >= FIRST_VERSION_WITH_EXTENDED_FLAGS
        }
    } catch (_: FileNotFoundException) {
        false
    } catch (_: EOFException) {
        true
    }
}
