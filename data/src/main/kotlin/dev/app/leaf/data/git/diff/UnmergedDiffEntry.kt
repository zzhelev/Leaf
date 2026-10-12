// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.conflicts.stagesOf
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.treewalk.filter.PathFilter

/**
 * The unstaged diff of a file with merge conflicts: the working tree file, conflict markers included, against "ours",
 * the version of the commit that the merge, rebase or cherry-pick stopped on (stage 2 in the index). For a file that
 * both sides changed, that's what `git diff --ours` shows. Returns null when [path] has no conflicts.
 *
 * Such a file has no stage 0 in the index, only stages 1 (the common ancestor), 2 (ours) and 3 (theirs), and JGit's
 * diff of the index against the working tree gives one entry per stage. Its first entry compared the common ancestor
 * with the working tree, so the diff showed lines that neither side has.
 *
 * When ours doesn't have the file (deleted by us), the diff adds the whole working file, where git shows nothing.
 * When the working file is ours as it is (as after a conflict where they deleted the file), there's nothing to show,
 * but an unchanged entry is still returned, so that the diff pane shows the file instead of closing.
 */
internal fun unmergedDiffEntry(git: Git, path: String): DiffEntry? {
    val stages = git.repository.readDirCache().stagesOf(path)

    if (stages.none { it.stage != DirCacheEntry.STAGE_0 }) {
        return null
    }

    val ours = stages.firstOrNull { it.stage == DirCacheEntry.STAGE_2 }
    val oursIndex = DirCache.newInCore()

    oursIndex.builder().apply {
        if (ours != null) {
            add(
                DirCacheEntry(path).apply {
                    fileMode = ours.fileMode
                    setObjectId(ours.objectId)
                }
            )
        }

        finish()
    }

    val entry = git.diff()
        .setOldTree(DirCacheIterator(oursIndex))
        .setPathFilter(PathFilter.create(path))
        .call()
        .firstOrNull { it.oldPath == path || it.newPath == path }

    return entry
        ?: ours?.let { UnchangedDiffEntry(path, it) }
        ?: throw MissingDiffEntryException("Neither ours nor the working tree has $path")
}

/**
 * A working tree file that is ours as it is, which JGit's diff leaves out. The working tree's id is zero, as git writes
 * it when it hasn't hashed the file (`index a3e6c8f..0000000`): JGit's `DiffFormatter` can't parse the header of an
 * entry whose two ids are the same. Its content is read from the working tree by path.
 */
private class UnchangedDiffEntry(path: String, ours: DirCacheEntry) : DiffEntry() {
    init {
        oldPath = path
        newPath = path
        oldMode = ours.fileMode
        newMode = ours.fileMode
        oldId = AbbreviatedObjectId.fromObjectId(ours.objectId)
        newId = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
        changeType = ChangeType.MODIFY
    }
}
