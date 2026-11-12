package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.conflicts.fileConflicts
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.extensions.flatListOf
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.NestedRepository
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import org.eclipse.jgit.api.Status as JGitStatus

private data class StatusParts(
    val status: JGitStatus,
    val conflicts: Map<String, FileConflict?>,
    val submodules: Set<String>,
    val nested: Map<String, NestedRepository>,
    /** Fork-only: the files added with `git add -N` that the status covers (see IntentToAdd). */
    val intentToAdd: Set<String>,
)

class GetStatusGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetStatusGitAction {
    /**
     * Fork-only: one status at a time per tab. A tab's refreshes already take turns, but the file watcher reads the
     * status on its own, when it starts and when folders appear, and two walks of the working tree at once only share
     * its disk and the CPU for no gain.
     */
    private val reading = Mutex()

    override suspend operator fun invoke(repository: String, paths: List<String>) = either {
        val (status, conflicts, submodules, nested, intentToAdd) =
            reading.withLock { readStatus(repository, paths) }.bind()

        val staged = getStaged(status, intentToAdd)
        val unstaged = getUnstaged(status, conflicts, submodules, nested, intentToAdd)

        Either.Ok(Status(staged, unstaged, status.ignoredNotInIndex.toList()))
    }

    private suspend fun readStatus(repository: String, paths: List<String>) =
        withContext(Dispatchers.IO) {
            jgit.provide(repository) { git ->
                val status = git
                    .status()
                    .apply {
                        for (path in paths) {
                            addPath(path)
                        }
                    }
                    .call()

                val nested = status.untracked.mapNotNull { path ->
                    nestedRepositoryOf(git.repository.workTree, git.repository.commonDirectory, path)?.let { path to it }
                }.toMap()

                StatusParts(
                    status,
                    fileConflicts(git, status.conflicting),
                    gitlinkPaths(git, status.modified + status.missing),
                    nested,
                    git.repository.intentToAddPaths().filterTo(mutableSetOf()) { it.isCoveredBy(paths) },
                )
            }
        }

    private fun getUnstaged(
        status: JGitStatus,
        conflicts: Map<String, FileConflict?>,
        submodules: Set<String>,
        nested: Map<String, NestedRepository>,
        intentToAdd: Set<String>,
    ): List<StatusEntry> {
        val untracked = status.untracked.map {
            StatusEntry(it, StatusType.ADDED, entryType = EntryType.UNSTAGED, nestedRepository = nested[it])
        }
        // Fork-only: a file added with `git add -N` is new in the working tree, as git status has it (` A`), where JGit
        // lists it as modified, or not at all while it's empty, as the index has the empty blob
        val modified = (status.modified - intentToAdd).map {
            StatusEntry(it, StatusType.MODIFIED, entryType = EntryType.UNSTAGED, isSubmodule = it in submodules)
        }
        val missing = status.missing.map {
            StatusEntry(
                it,
                StatusType.REMOVED,
                entryType = EntryType.UNSTAGED,
                isSubmodule = it in submodules,
                isIntentToAdd = it in intentToAdd,
            )
        }
        val intentToAddFiles = (intentToAdd - status.missing).map {
            StatusEntry(it, StatusType.ADDED, entryType = EntryType.UNSTAGED, isIntentToAdd = true)
        }
        val conflicting = status.conflicting.map {
            StatusEntry(it, StatusType.CONFLICTING, entryType = EntryType.UNSTAGED, conflict = conflicts[it])
        }

        return flatListOf(
            untracked,
            modified,
            missing,
            intentToAddFiles,
            conflicting,
        ).sortedBy { it.filePath }
    }

    /**
     * Fork-only: without the files added with `git add -N`, whose content isn't staged. One that HEAD has is staged as
     * deleted, as git commit leaves it out (`DA` in git status); JGit lists it as changed, or not at all when HEAD's
     * version is empty too. A path that JGit lists as added isn't in HEAD.
     */
    private fun getStaged(status: JGitStatus, intentToAdd: Set<String>): List<StatusEntry> {
        val added = (status.added - intentToAdd).toStatusEntries(StatusType.ADDED, EntryType.STAGED)
        val modified = (status.changed - intentToAdd).toStatusEntries(StatusType.MODIFIED, EntryType.STAGED)
        val removed = (status.removed + (intentToAdd - status.added))
            .toStatusEntries(StatusType.REMOVED, EntryType.STAGED)

        return flatListOf(
            added,
            modified,
            removed,
        ).sortedBy { it.filePath }
    }

    private fun Set<String>.toStatusEntries(statusType: StatusType, entryType: EntryType): List<StatusEntry> {
        return this
            .map {
                StatusEntry(it, statusType, entryType)
            }
    }
}

/**
 * Whether the status that [paths] asks for covers [this] path: every path when it's empty, as JGit's status reads it,
 * else a path that is one of them or under one of them.
 */
private fun String.isCoveredBy(paths: List<String>): Boolean =
    paths.isEmpty() || paths.any { it.isEmpty() || this == it || startsWith(it.trimEnd('/') + "/") }
