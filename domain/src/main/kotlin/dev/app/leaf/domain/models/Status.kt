package dev.app.leaf.domain.models

import androidx.compose.runtime.Immutable

data class Status(
    val staged: List<StatusEntry> = emptyList(),
    val unstaged: List<StatusEntry> = emptyList(),
    val ignored: List<String> = emptyList(),
)


/** [conflict] is set for a [StatusType.CONFLICTING] entry (fork-only). */
data class StatusEntry(
    val filePath: String,
    val statusType: StatusType,
    val entryType: EntryType = EntryType.UNSTAGED,
    val conflict: FileConflict? = null,
)

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