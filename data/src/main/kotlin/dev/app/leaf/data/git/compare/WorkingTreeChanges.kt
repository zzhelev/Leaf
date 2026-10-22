// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

import dev.app.leaf.data.git.diff.withRenames
import org.eclipse.jgit.diff.ContentSource
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.treewalk.filter.TreeFilter

/** The files that differ from a tree to the working tree, and those of them that git doesn't track yet. */
internal data class WorkingTreeChanges(val changes: List<DiffEntry>, val untracked: Set<String>)

/**
 * The files from [oldTree] to the working tree (fork-only, `docs/fork/compare.md`, 7.2), as `git diff <tree>` and
 * `git ls-files --others --exclude-standard` list them together: tracked files as they are on disk, deleted ones, and
 * new files that git doesn't ignore, with moved files as renames.
 *
 * One walk of the tree, the index and the files on disk. The index is there for two things that a walk of the tree and
 * the disk alone can't do: telling an untracked file from a tracked one, so that an ignored folder that nothing tracks
 * isn't walked into (a `build/` or `node_modules/` would be read whole), and git's stat cache, which gives a file that
 * didn't change since it was staged its id without reading it.
 */
internal suspend fun Repository.workingTreeChanges(oldTree: RevTree): WorkingTreeChanges {
    val changes = mutableListOf<DiffEntry>()
    val untracked = mutableSetOf<String>()

    newObjectReader().use { reader ->
        TreeWalk(this, reader).use { walk ->
            val old = walk.addTree(CanonicalTreeParser(null, reader, oldTree))
            val index = walk.addTree(DirCacheIterator(readDirCache()))
            val work = walk.addTree(FileTreeIterator(this))

            walk.getTree(work, WorkingTreeIterator::class.java).setDirCacheIterator(walk, index)
            walk.isRecursive = true
            walk.filter = WorkingTreeDiffFilter(old, index, work)

            while (walk.next()) {
                val oldMode = walk.getFileMode(old)
                val newMode = walk.workMode(work, index)
                val path = walk.pathString

                changes += WorkingTreeEntry(
                    oldPath = path.takeIf { oldMode != FileMode.MISSING },
                    newPath = path.takeIf { newMode != FileMode.MISSING },
                    oldMode = oldMode,
                    newMode = newMode,
                    oldId = walk.getObjectId(old),
                    newId = if (newMode == FileMode.MISSING) ObjectId.zeroId() else walk.getObjectId(work),
                )

                if (newMode != FileMode.MISSING && walk.getRawMode(index) == 0) {
                    untracked += path
                }
            }
        }
    }

    // The files on disk have no blobs, so a file renamed and edited is read from there
    return WorkingTreeChanges(withRenames(changes, newSource = ContentSource.create(FileTreeIterator(this))), untracked)
}

/**
 * What the walk keeps: a file whose mode or content differs between the tree and the disk, and the folders that may hold
 * one. An untracked path that git ignores stays out, a folder too, so the walk never goes into it.
 */
private class WorkingTreeDiffFilter(private val old: Int, private val index: Int, private val work: Int) : TreeFilter() {
    override fun include(walker: TreeWalk): Boolean {
        val disk = walker.getTree(work, WorkingTreeIterator::class.java)
        val isTracked = walker.getRawMode(old) != 0 || walker.getRawMode(index) != 0

        if (disk != null && !isTracked && disk.isEntryIgnored) return false

        if (walker.isSubtree) return true

        val oldMode = walker.getFileMode(old)
        val newMode = walker.workMode(work, index)

        return when {
            oldMode == FileMode.MISSING && newMode == FileMode.MISSING -> false
            oldMode != newMode -> true
            else -> !walker.idEqual(old, work)
        }
    }

    override fun shouldBeRecursive(): Boolean = true

    override fun clone(): TreeFilter = this
}

/** The file's mode on disk as git records it (`core.fileMode`, links), or missing where there's no file. */
private fun TreeWalk.workMode(work: Int, index: Int): FileMode {
    val disk = getTree(work, WorkingTreeIterator::class.java) ?: return FileMode.MISSING

    return disk.getIndexFileMode(getTree(index, DirCacheIterator::class.java))
}

/** An entry of [workingTreeChanges]: JGit's own constructors are its package's. */
private class WorkingTreeEntry(
    oldPath: String?,
    newPath: String?,
    oldMode: FileMode,
    newMode: FileMode,
    oldId: ObjectId,
    newId: ObjectId,
) : DiffEntry() {
    init {
        this.changeType = when {
            oldPath == null -> ChangeType.ADD
            newPath == null -> ChangeType.DELETE
            else -> ChangeType.MODIFY
        }
        this.oldPath = oldPath ?: DEV_NULL
        this.newPath = newPath ?: DEV_NULL
        this.oldMode = oldMode
        this.newMode = newMode
        this.oldId = AbbreviatedObjectId.fromObjectId(oldId)
        this.newId = AbbreviatedObjectId.fromObjectId(newId)
    }
}
