package dev.app.leaf.domain.models

import androidx.compose.runtime.Immutable

data class Status(
    val staged: List<StatusEntry> = emptyList(),
    val unstaged: List<StatusEntry> = emptyList(),
    val ignored: List<String> = emptyList(),
)


/**
 * [conflict] is set for a [StatusType.CONFLICTING] entry (fork-only). [isSubmodule] is set for a submodule that
 * changed, which the index records as a gitlink: its commit moved, or its folder is gone (fork-only).
 * [nestedRepository] is set for an untracked entry that is a folder holding another repository (fork-only).
 */
data class StatusEntry(
    val filePath: String,
    val statusType: StatusType,
    val entryType: EntryType = EntryType.UNSTAGED,
    val conflict: FileConflict? = null,
    val isSubmodule: Boolean = false,
    val nestedRepository: NestedRepository? = null,
)

/**
 * What an untracked folder holds that is another repository, which the Unstaged list shows as one row (fork-only):
 * JGit doesn't list its files.
 */
enum class NestedRepository {
    /** A linked worktree of this repository, such as an agent's `.claude/worktrees/<name>`. */
    LINKED_WORKTREE,

    /** Any other repository: a clone, or a worktree of another repository. */
    REPOSITORY,
}

enum class StatusType {
    ADDED,
    MODIFIED,
    REMOVED,
    CONFLICTING,
}

@Immutable
data class StatusSummary(
    val modifiedCount: Int,
    val deletedCount: Int,
    val addedCount: Int,
    val conflictingCount: Int,
) {
    val total = modifiedCount +
            deletedCount +
            addedCount +
            conflictingCount
}