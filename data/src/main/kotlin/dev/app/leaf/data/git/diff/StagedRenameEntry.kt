// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.workspace.leaveIntentToAddOut
import dev.app.leaf.domain.sorting.RenamedFrom
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.NullOutputStream

/**
 * The diff entry of a staged rename (fork-only, `docs/fork/status-renames.md`): from [renamedFrom]'s path in HEAD to
 * [newPath] in the index, which the diff pane reads by blob id, as a commit's renamed file. Null when HEAD no longer has
 * the old path or the index the new one, as after a commit or a reset that the list hasn't caught up with.
 */
internal fun stagedRenameEntry(repository: Repository, renamedFrom: RenamedFrom, newPath: String): DiffEntry? {
    val head = repository.resolve("HEAD^{tree}") ?: return null

    val entries = repository.newObjectReader().use { reader ->
        DiffFormatter(NullOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(false)
            formatter.pathFilter = PathFilterGroup.createFromStrings(renamedFrom.path, newPath)
            // Without intent-to-add entries, as for the list (GetStagedRenamesGitAction): the old path may be one
            val index = repository.readDirCache().apply { leaveIntentToAddOut() }
            formatter.scan(CanonicalTreeParser(null, reader, head), DirCacheIterator(index))
        }
    }

    val deleted = entries.firstOrNull { it.changeType == DiffEntry.ChangeType.DELETE && it.oldPath == renamedFrom.path }
    val added = entries.firstOrNull { it.changeType == DiffEntry.ChangeType.ADD && it.newPath == newPath }

    return if (deleted != null && added != null) RenameEntry(deleted, added, renamedFrom.similarity) else null
}

/** The deletion of a file and the addition of where it moved, as one rename, as JGit's rename detector makes them. */
private class RenameEntry(deleted: DiffEntry, added: DiffEntry, similarity: Int) : DiffEntry() {
    init {
        changeType = ChangeType.RENAME
        oldPath = deleted.oldPath
        oldId = deleted.oldId
        oldMode = deleted.oldMode
        newPath = added.newPath
        newId = added.newId
        newMode = added.newMode
        score = similarity
    }
}
