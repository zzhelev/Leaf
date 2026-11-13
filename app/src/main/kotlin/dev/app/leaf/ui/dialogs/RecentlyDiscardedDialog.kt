// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.TabViewModel
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotices
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.sorting.formatTimeAgo
import dev.app.leaf.domain.usecases.GetDiscardRecordsUseCase
import dev.app.leaf.domain.usecases.PlanDiscardRestoreUseCase
import dev.app.leaf.domain.usecases.RestoreDiscardUseCase
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import dev.app.leaf.ui.undoDiscard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import javax.inject.Inject

/**
 * The discards that Leaf keeps for the tab's worktree (fork-only, upstream issue 39), newest first, read again after
 * each discard and each Undo.
 */
class RecentlyDiscardedViewModel @Inject constructor(
    private val getDiscardRecordsUseCase: GetDiscardRecordsUseCase,
    private val planDiscardRestoreUseCase: PlanDiscardRestoreUseCase,
    private val restoreDiscardUseCase: RestoreDiscardUseCase,
    discardNotices: DiscardNotices,
) : TabViewModel() {
    /** Null until they're read. */
    val records: StateFlow<List<DiscardRecord>?>
        field = MutableStateFlow<List<DiscardRecord>?>(null)

    init {
        viewModelScope.launch {
            discardNotices.latest.collect { load() }
        }
    }

    suspend fun plan(record: DiscardRecord): DiscardRestorePlan? = planDiscardRestoreUseCase(record).okOrNull()

    fun restore(record: DiscardRecord) {
        viewModelScope.launch {
            restoreDiscardUseCase(record).await()
            load()
        }
    }

    private suspend fun load() {
        records.value = getDiscardRecordsUseCase().okOrNull().orEmpty()
    }
}

/** "Recently discarded…" of Unstaged's ⋯ menu and the Actions list: each kept discard, with Restore. */
@Composable
fun RecentlyDiscardedDialog(
    viewModel: RecentlyDiscardedViewModel,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val records by viewModel.records.collectAsState()
    val scope = rememberCoroutineScope()

    MaterialDialog(onCloseRequested = onDismiss) {
        RecentlyDiscardedContent(
            records = records,
            now = System.currentTimeMillis(),
            onRestore = { record ->
                scope.launch {
                    undoDiscard(record, viewModel::plan, viewModel::restore, onConfirmAction)
                }
            },
            onClose = onDismiss,
        )
    }
}

/** [RecentlyDiscardedDialog]'s content, which a test renders without a window. */
@Composable
fun RecentlyDiscardedContent(
    records: List<DiscardRecord>?,
    now: Long,
    onRestore: (DiscardRecord) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.width(600.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.recently_discarded_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
            )

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                painterResource(Res.drawable.history),
                contentDescription = null,
                tint = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(24.dp),
            )
        }

        if (records != null && records.isEmpty()) {
            Text(
                text = stringResource(Res.string.recently_discarded_empty),
                color = MaterialTheme.colors.onBackgroundSecondary,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .padding(top = 12.dp)
                .heightIn(max = 420.dp),
        ) {
            items(records.orEmpty(), key = { it.id }) { record ->
                DiscardRow(record, now, onRestore = { onRestore(record) })
            }
        }

        PrimaryButton(
            text = stringResource(Res.string.recently_discarded_close),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 24.dp),
        )
    }
}

@Composable
private fun DiscardRow(record: DiscardRecord, now: Long, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painterResource(
                when (record.kind) {
                    DiscardKind.HUNK, DiscardKind.LINE -> Res.drawable.remove
                    else -> Res.drawable.delete
                }
            ),
            contentDescription = null,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(16.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = discardRowTitle(record),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = discardRowDetail(record),
                color = MaterialTheme.colors.onBackgroundSecondary,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = formatTimeAgo(record.time, now),
            color = MaterialTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.caption,
        )

        SecondaryButton(
            text = stringResource(Res.string.recently_discarded_restore),
            onClick = onRestore,
        )
    }
}

/** A file's name, or how many files. */
@Composable
internal fun discardRowTitle(record: DiscardRecord): String {
    val count = record.paths.size

    return if (count == 1) {
        record.paths.single().substringAfterLast('/')
    } else {
        pluralStringResource(Res.plurals.recently_discarded_files, count, count)
    }
}

/** Which discard, then a file's folder or the files' names. */
@Composable
internal fun discardRowDetail(record: DiscardRecord): String {
    val kind = when (record.kind) {
        DiscardKind.CHANGES -> stringResource(Res.string.recently_discarded_kind_changes)
        DiscardKind.STAGED_CHANGES -> stringResource(Res.string.recently_discarded_kind_staged_changes)
        DiscardKind.ALL -> stringResource(Res.string.recently_discarded_kind_all)
        DiscardKind.HUNK -> stringResource(Res.string.recently_discarded_kind_hunk)
        DiscardKind.LINE -> stringResource(Res.string.recently_discarded_kind_line)
        DiscardKind.DELETE -> stringResource(Res.string.recently_discarded_kind_deleted)
    }
    val deleted = record.deletedPaths.size
    val newFiles = if (record.kind == DiscardKind.ALL && deleted > 0) {
        pluralStringResource(Res.plurals.recently_discarded_deleted_new_files, deleted, deleted)
    } else {
        null
    }
    val where = if (record.paths.size == 1) {
        record.paths.single().substringBeforeLast('/', missingDelimiterValue = "")
    } else {
        record.paths.joinToString(", ") { it.substringAfterLast('/') }
    }

    return listOf(kind, newFiles, where).filter { !it.isNullOrEmpty() }.joinToString(" · ")
}
