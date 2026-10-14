// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.RenamedFile
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** git's rename threshold: half of the lines alike. */
private const val RENAME_SCORE = 50

/**
 * The files that each side renamed differently, from the index (`conflict-resolver.md`, section 9). git records each
 * as three conflicts: the old name at stage 1 alone, ours' new name at stage 2 alone, theirs' at stage 3 alone. It
 * writes the same merged content at both new names, so they pair by their blob. The old name joins the pair whose
 * content is like its own (JGit's rename detection, at git's 50%), or as the only one left. A path that pairs with
 * nothing, or with more than one, stays out.
 */
internal fun renamedFiles(repository: Repository, index: DirCache): List<RenamedFile> {
    val alone = mutableMapOf<Int, MutableList<DirCacheEntry>>()

    for (i in 0 until index.entryCount) {
        val entry = index.getEntry(i)

        if (entry.stage != DirCacheEntry.STAGE_0 && index.stagesOf(entry.pathString).size == 1) {
            alone.getOrPut(entry.stage) { mutableListOf() } += entry
        }
    }

    val originals = alone[DirCacheEntry.STAGE_1].orEmpty()
    val ours = alone[DirCacheEntry.STAGE_2].orEmpty()
    val theirs = alone[DirCacheEntry.STAGE_3].orEmpty()

    // A pair: the one entry of theirs with ours' blob, and the other way round
    val pairs = ours.mapNotNull { our ->
        val their = theirs.singleOrNull { it.objectId == our.objectId } ?: return@mapNotNull null

        their.takeIf { ours.count { it.objectId == our.objectId } == 1 }?.let { our to it }
    }

    if (pairs.isEmpty() || originals.isEmpty()) {
        return emptyList()
    }

    val originalOf = if (pairs.size == 1 && originals.size == 1) {
        mapOf(pairs.single().first.pathString to originals.single())
    } else {
        renamesOf(repository, originals, pairs.map { it.first })
    }

    return pairs.mapNotNull { (our, their) ->
        originalOf[our.pathString]?.let { RenamedFile(it.pathString, our.pathString, their.pathString) }
    }
}

/** Which of [originals] each of [renamed] came from, by content, as `git diff -M` pairs them. */
private fun renamesOf(
    repository: Repository,
    originals: List<DirCacheEntry>,
    renamed: List<DirCacheEntry>,
): Map<String, DirCacheEntry> {
    val detector = RenameDetector(repository).apply {
        renameScore = RENAME_SCORE
        addAll(originals.map { IndexEntryChange.deleted(it) } + renamed.map { IndexEntryChange.added(it) })
    }
    val byPath = originals.associateBy { it.pathString }

    // Only a rename has an old name that's one of the originals and a new name
    return detector.compute()
        .mapNotNull { rename -> byPath[rename.oldPath]?.let { rename.newPath to it } }
        .toMap()
}

/** A deletion or an addition of an index entry, for [RenameDetector], whose own factories JGit keeps to itself. */
private class IndexEntryChange private constructor(entry: DirCacheEntry, added: Boolean) : DiffEntry() {
    init {
        val id = AbbreviatedObjectId.fromObjectId(entry.objectId)
        val none = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())

        changeType = if (added) ChangeType.ADD else ChangeType.DELETE
        oldPath = if (added) DEV_NULL else entry.pathString
        newPath = if (added) entry.pathString else DEV_NULL
        oldId = if (added) none else id
        newId = if (added) id else none
        oldMode = if (added) FileMode.MISSING else entry.fileMode
        newMode = if (added) entry.fileMode else FileMode.MISSING
    }

    companion object {
        fun deleted(entry: DirCacheEntry): DiffEntry = IndexEntryChange(entry, added = false)
        fun added(entry: DirCacheEntry): DiffEntry = IndexEntryChange(entry, added = true)
    }
}

/**
 * Whether [file] has a conflict marker that opens and one that closes, of any size: git makes them longer than the
 * attribute says for a file renamed on both sides (eight characters, where it writes seven elsewhere).
 */
internal fun hasConflictMarkers(file: File): Boolean {
    val path = file.toPath()

    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return false
    }

    val text = String(Files.readAllBytes(path), Charsets.ISO_8859_1)

    return OPENING_MARKER.containsMatchIn(text) && CLOSING_MARKER.containsMatchIn(text)
}

private val OPENING_MARKER = Regex("(?m)^<{7,} ")
private val CLOSING_MARKER = Regex("(?m)^>{7,} ")

/**
 * The commit that theirs is in the operation that stopped, as [conflictSides] reads it: the commit merged, replayed in
 * a rebase, or picked. Null for a revert, whose theirs is a commit's parent with its changes undone, and for anything
 * else.
 */
internal fun theirsCommit(repository: Repository): ObjectId? = when (repository.repositoryState) {
    RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> repository.readMergeHeads()?.firstOrNull()
    RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED -> repository.readCherryPickHead()
    RepositoryState.REBASING,
    RepositoryState.REBASING_REBASING,
    RepositoryState.REBASING_MERGE,
    RepositoryState.REBASING_INTERACTIVE -> rebasedCommit(repository)

    else -> null
}

/** The commit that a rebase replays: `REBASE_HEAD`, or JGit's `rebase-merge/stopped-sha`. */
internal fun rebasedCommit(repository: Repository): ObjectId? {
    val rebaseDir = listOf("rebase-merge", "rebase-apply")
        .map { File(repository.directory, it) }
        .firstOrNull { it.isDirectory }
        ?: return null
    val replayed = listOf(File(repository.directory, "REBASE_HEAD"), File(rebaseDir, "stopped-sha"))
        .firstNotNullOfOrNull { file -> file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }

    return replayed?.let { repository.resolve(it) }
}

/** The blob at [path] in [commit]'s tree, or null. */
internal fun Repository.blobAt(commit: ObjectId, path: String): ObjectId? =
    resolve("${commit.name}:$path")?.takeIf { id -> newObjectReader().use { it.open(id).type == Constants.OBJ_BLOB } }
