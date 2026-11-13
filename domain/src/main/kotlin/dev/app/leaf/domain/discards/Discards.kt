// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.discards

import dev.app.leaf.common.TabScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** How long Leaf keeps what a discard threw away, so that it can be undone (upstream issue 39). */
const val DISCARD_KEPT_DAYS = 14

/** What the kept discards of a worktree may take on disk. Past it, the oldest go first, but never the newest. */
const val MAX_DISCARDED_BYTES = 1L shl 30

/** Which discard threw the changes away. */
enum class DiscardKind {
    /** Discard of files' unstaged changes: a file, a selection or a folder in Unstaged. */
    CHANGES,

    /** Discard in Staged, which resets the index entries too. */
    STAGED_CHANGES,

    /** "Discard all changes…" of Unstaged, which may delete new files too. */
    ALL,

    HUNK,
    LINE,

    /** "Delete file" on an untracked file, or a folder that holds another repository. */
    DELETE,
}

/**
 * A discard whose lost changes Leaf kept (fork-only, upstream issue 39), in a folder of the worktree's git dir, for
 * [DISCARD_KEPT_DAYS] days.
 *
 * @param id the record's folder, which sorts as the records' times do.
 * @param gitDir the git dir of the worktree it was made in, where it's kept.
 * @param time when, in milliseconds since the epoch.
 * @param paths the files that it changed, from the working tree's root, in the order the discard was given them. A path
 * whose file the discard didn't change isn't in it, as an untracked file in a selection.
 * @param deletedPaths those of [paths] that it deleted: new files, or what "Delete file" deleted.
 */
data class DiscardRecord(
    val id: String,
    val gitDir: String,
    val time: Long,
    val kind: DiscardKind,
    val paths: List<String>,
    val deletedPaths: List<String>,
)

/** How Undo can bring a file of a [DiscardRecord] back. */
enum class RestoreWay {
    /** The file, and its index entry, are as the discard left them: they go back as they were. */
    AS_IT_WAS,

    /**
     * The file changed since, in lines that the discard didn't touch: the discarded lines go back into it, as a 3-way
     * merge of the file, the discarded version and the discard's result.
     */
    MERGED,

    /** The file, and its index entry, are as they were before the discard already. */
    ALREADY_BACK,

    /** The file, or its index entry, changed since in a way that Leaf can't merge: it stays as it is. */
    CHANGED_SINCE,
}

/** What Undo would do with each file of [record], before it does it. */
data class DiscardRestorePlan(val record: DiscardRecord, val ways: Map<String, RestoreWay>) {
    val changedSince: List<String> = record.paths.filter { ways[it] == RestoreWay.CHANGED_SINCE }
    val restorable: List<String> = record.paths.filter { it in ways && ways[it] != RestoreWay.CHANGED_SINCE }
}

/** What Undo did: the files of [record] that are back, and those [left] as they were, as they changed since. */
data class DiscardRestore(val record: DiscardRecord, val restored: List<String>, val left: List<String>)

/** The latest discard of the tab, which its strip shows until dismissed, with Undo's outcome once it ran. */
data class DiscardNotice(val record: DiscardRecord, val restore: DiscardRestore? = null)

/**
 * The tab's latest discard (fork-only, upstream issue 39), which the discards set as they keep their record, and Undo
 * marks as restored.
 */
@TabScope
class DiscardNotices @Inject constructor() {
    val latest: StateFlow<DiscardNotice?>
        field = MutableStateFlow<DiscardNotice?>(null)

    fun recorded(record: DiscardRecord) {
        latest.value = DiscardNotice(record)
    }

    /** Marks the notice of [restore]'s record, if it's the one shown. A record restored from the list may be older. */
    fun restored(restore: DiscardRestore) {
        latest.update { notice ->
            if (notice?.record?.id == restore.record.id && notice.record.gitDir == restore.record.gitDir) {
                notice.copy(restore = restore)
            } else {
                notice
            }
        }
    }

    fun dismiss() {
        latest.value = null
    }
}
