// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.FileConflict
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode

/** The index entries of [path]: stage 0 alone, or the stages 1 to 3 of a conflict, without the ones a side deleted. */
internal fun DirCache.stagesOf(path: String): List<DirCacheEntry> {
    val first = findEntry(path)

    return if (first < 0) emptyList() else (first until nextEntry(first)).map { getEntry(it) }
}

/** The entry that [side] has in the conflict of [path] (stage 2 or 3), or null when that side deleted the file. */
internal fun DirCache.conflictEntry(path: String, side: ConflictSide): DirCacheEntry? {
    val stage = when (side) {
        ConflictSide.OURS -> DirCacheEntry.STAGE_2
        ConflictSide.THEIRS -> DirCacheEntry.STAGE_3
    }

    return stagesOf(path).firstOrNull { it.stage == stage }
}

/** The conflict of [path], or null when it has none: the index has it at stage 0, or not at all. */
internal fun DirCache.fileConflict(path: String): FileConflict? {
    val stages = stagesOf(path)

    if (stages.none { it.stage != DirCacheEntry.STAGE_0 }) {
        return null
    }

    return FileConflict(
        ours = stages.firstOrNull { it.stage == DirCacheEntry.STAGE_2 }?.fileMode?.toConflictVersion(),
        theirs = stages.firstOrNull { it.stage == DirCacheEntry.STAGE_3 }?.fileMode?.toConflictVersion(),
    )
}

/** The conflicts of [paths], read from the index once. Status lists them as conflicting (fork-only). */
internal fun fileConflicts(git: Git, paths: Set<String>): Map<String, FileConflict?> {
    if (paths.isEmpty()) {
        return emptyMap()
    }

    val index = git.repository.readDirCache()

    return paths.associateWith { index.fileConflict(it) }
}

/** Those of [paths] that have conflicts in the index. */
internal fun conflictedPaths(git: Git, paths: List<String>): List<String> {
    val index = git.repository.readDirCache()

    return paths.filter { index.fileConflict(it) != null }
}

private fun FileMode.toConflictVersion() = when (this) {
    FileMode.SYMLINK -> ConflictVersion.SYMLINK
    FileMode.GITLINK -> ConflictVersion.SUBMODULE
    else -> ConflictVersion.FILE
}
