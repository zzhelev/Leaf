// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.diff.RenameSettings
import dev.app.leaf.data.git.diff.statusRenameSettings
import dev.app.leaf.data.git.diff.withRenames
import dev.app.leaf.domain.interfaces.IGetStagedRenamesGitAction
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.RenamedFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.NullOutputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** A file that the index moved from [oldPath] to [newPath], [similarity] percent alike. */
internal data class StagedRename(val oldPath: String, val newPath: String, val similarity: Int)

/**
 * Staged's files that the index moved, as `git status` pairs them (fork-only, upstream issue 347,
 * `docs/fork/status-renames.md`). The candidates are Staged's added and deleted files, compared from HEAD to the index
 * with the commit lists' stages, limit and time budget ([withRenames]), and `status.renames` and `status.renameLimit`.
 *
 * - Only those: a deleted tracked file and an untracked one are never paired, as git doesn't, nor is a path with
 *   conflicts, which the status lists in Unstaged, nor an intent-to-add file, which git shows in the working tree's
 *   column. The index is read without intent-to-add entries, as git reads it to compare it with HEAD.
 * - The pairs are kept for the candidates they were found from, by path and blob: saving files in the working tree
 *   doesn't change them, so the refreshes while an agent writes read nothing. A result that ran out of time is kept
 *   too, so the rows don't change between two refreshes of the same index.
 * - Tab-scoped, as every git action, so each tab keeps its own.
 */
class GetStagedRenamesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetStagedRenamesGitAction {
    private val last = AtomicReference<FoundRenames?>(null)

    override suspend operator fun invoke(repositoryPath: String, staged: List<StatusEntry>) =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                staged.pairedAs(renamesIn(git.repository, staged))
            }
        }

    private suspend fun renamesIn(repository: Repository, staged: List<StatusEntry>): List<StagedRename> {
        val removed = staged.filter { it.statusType == StatusType.REMOVED }.map { it.filePath }
        val added = staged.filter { it.statusType == StatusType.ADDED }.map { it.filePath }

        if (removed.isEmpty() || added.isEmpty()) {
            return emptyList()
        }

        val settings = repository.statusRenameSettings()

        if (!settings.isEnabled) {
            return emptyList()
        }

        val head = repository.resolve("HEAD^{tree}") ?: return emptyList()
        // An intent-to-add entry isn't staged: git compares HEAD with the index without it, so a moved file's old path
        // that `git add -N` added again is still the move's deletion (`R  a -> b` and ` A a` in git status)
        val index = repository.readDirCache().apply { leaveIntentToAddOut() }

        val candidates = scan(repository, head, index, removed + added)
            .filter { it.changeType == ChangeType.ADD || it.changeType == ChangeType.DELETE }
        val key = FoundRenames.Key(settings, candidates.map { it.described() }.toSet())

        last.get()?.takeIf { it.key == key }?.let { return it.renames }

        val renames = repository.withRenames(candidates, settings = settings)
            .filter { it.changeType == ChangeType.RENAME }
            .map { StagedRename(it.oldPath, it.newPath, it.score) }

        last.set(FoundRenames(key, renames))

        return renames
    }

    /** HEAD against the index, for [paths] only: no walk of the whole tree. */
    private fun scan(repository: Repository, head: ObjectId, index: DirCache, paths: List<String>): List<DiffEntry> =
        repository.newObjectReader().use { reader ->
            DiffFormatter(NullOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                // setRepository switches detection on from diff.renames
                formatter.setDetectRenames(false)
                formatter.pathFilter = PathFilterGroup.createFromStrings(paths)
                formatter.scan(CanonicalTreeParser(null, reader, head), DirCacheIterator(index))
            }
        }

    private fun DiffEntry.described() =
        "$changeType $oldPath $newPath ${oldId.name()} ${newId.name()} ${oldMode.bits} ${newMode.bits}"

    private class FoundRenames(val key: Key, val renames: List<StagedRename>) {
        data class Key(val settings: RenameSettings, val candidates: Set<String>)
    }
}

/**
 * These entries, Staged's, with each of [renames] as one [StatusType.RENAMED] entry in place of the deletion of its
 * old path and the addition of its new one, in the status's order.
 */
internal fun List<StatusEntry>.pairedAs(renames: List<StagedRename>): List<StatusEntry> {
    if (renames.isEmpty()) {
        return this
    }

    val oldPaths = renames.map { it.oldPath }.toSet()
    val newPaths = renames.map { it.newPath }.toSet()
    val kept = filterNot {
        (it.statusType == StatusType.REMOVED && it.filePath in oldPaths) ||
                (it.statusType == StatusType.ADDED && it.filePath in newPaths)
    }
    val renamed = renames.map {
        StatusEntry(
            it.newPath,
            StatusType.RENAMED,
            EntryType.STAGED,
            renamedFrom = RenamedFrom(it.oldPath, it.similarity),
        )
    }

    return (kept + renamed).sortedBy { it.filePath }
}
