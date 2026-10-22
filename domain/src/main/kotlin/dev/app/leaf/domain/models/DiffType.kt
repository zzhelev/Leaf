package dev.app.leaf.domain.models

import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.extensions.toStatusType
import org.eclipse.jgit.diff.DiffEntry
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
sealed class DiffType {
    abstract val filePath: String
    abstract val statusType: StatusType

    /**
     * Two versions read by blob id, as a commit's file. Fork-only: with [readsWorkingTree], the new side is the file on
     * disk, for a comparison with the working tree (`docs/fork/compare.md`, 7.2), which has no blob for it.
     */
    data class CommitDiff(val diffEntry: DiffEntry, val readsWorkingTree: Boolean = false) : DiffType() {
        override val filePath: String
            get() = diffEntry.filePath

        override val statusType: StatusType
            get() = diffEntry.toStatusType()
    }

    data class UncommittedDiff(val statusEntry: StatusEntry, val entryType: EntryType) : DiffType() {
        override val filePath: String
            get() = statusEntry.filePath

        override val statusType: StatusType
            get() = statusEntry.statusType
    }

    val isStagedDiff: Boolean
        get() {
            contract { returns(true) implies (this@DiffType is UncommittedDiff) }
            return this is UncommittedDiff && this.entryType == EntryType.STAGED
        }

    val isUnstagedDiff: Boolean
        get() {
            contract { returns(true) implies (this@DiffType is UncommittedDiff) }
            return this is UncommittedDiff && this.entryType == EntryType.UNSTAGED
        }

}
