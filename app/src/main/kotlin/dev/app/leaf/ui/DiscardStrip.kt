// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotice
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.dialogs.ConfirmableAction
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The strip under the toolbar after a discard (fork-only, upstream issue 39): what was discarded, Undo right after it,
 * and ×. It stays until it's closed or the next discard, as a toast would be gone before Undo was found. After Undo, it
 * says what is back. Older discards are in "Recently discarded".
 */
@Composable
fun DiscardStrip(
    notice: DiscardNotice,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strip = MaterialTheme.styleColors.successStrip
    val isUndone = notice.restore != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(strip.background)
            .bottomEdge(strip.border)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(if (isUndone) Res.drawable.undo else Res.drawable.delete),
            contentDescription = null,
            tint = strip.icon,
            modifier = Modifier.size(16.dp),
        )

        // The button follows the text, as on the other strips that offer an action
        Row(
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = discardStripText(notice),
                color = strip.content,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (!isUndone) {
                SecondaryButton(
                    text = stringResource(Res.string.discard_strip_undo),
                    colors = strip.button,
                    onClick = onUndo,
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(32.dp)
                .handOnHover(),
        ) {
            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = stringResource(Res.string.discard_strip_dismiss),
                tint = strip.content,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** What was discarded, with a file's name when there's one, or after Undo, what is back. */
@Composable
internal fun discardStripText(notice: DiscardNotice): String {
    val restore = notice.restore

    if (restore != null) {
        val restored = restore.restored.size
        val total = restored + restore.left.size

        return when {
            restore.left.isNotEmpty() -> stringResource(Res.string.discard_strip_back_partly, restored, total)
            restored == 1 -> stringResource(Res.string.discard_strip_back_one, fileName(restore.restored.single()))
            else -> pluralStringResource(Res.plurals.discard_strip_back, restored, restored)
        }
    }

    val record = notice.record
    val first = fileName(record.paths.first())

    return when (record.kind) {
        DiscardKind.HUNK -> stringResource(Res.string.discard_strip_hunk, first)
        DiscardKind.LINE -> stringResource(Res.string.discard_strip_line, first)
        DiscardKind.DELETE -> stringResource(Res.string.discard_strip_deleted_one, first)
        DiscardKind.CHANGES, DiscardKind.STAGED_CHANGES, DiscardKind.ALL -> {
            val changed = record.paths.size - record.deletedPaths.size
            val deleted = record.deletedPaths.size

            when {
                deleted == 0 && changed == 1 -> stringResource(Res.string.discard_strip_changes_of, first)
                deleted == 0 -> pluralStringResource(Res.plurals.discard_strip_changes, changed, changed)
                changed == 0 && deleted == 1 ->
                    stringResource(Res.string.discard_strip_deleted_one, fileName(record.deletedPaths.single()))

                changed == 0 -> pluralStringResource(Res.plurals.discard_strip_deleted, deleted, deleted)
                else -> stringResource(
                    Res.string.discard_strip_changes_and_deleted,
                    pluralStringResource(Res.plurals.discard_strip_files, changed, changed),
                    pluralStringResource(Res.plurals.discard_strip_new_files, deleted, deleted),
                )
            }
        }
    }
}

private fun fileName(path: String) = path.substringAfterLast('/')

/**
 * Undo of [record], from the strip or "Recently discarded": when some of its files changed since in ways that can't be
 * merged, and others can come back, asks first ([ConfirmableAction.RestoreDiscardPartly]). Otherwise it restores at
 * once: a record that nothing of can come back fails the task, whose error dialog names the files that changed.
 */
internal suspend fun undoDiscard(
    record: DiscardRecord,
    plan: suspend (DiscardRecord) -> DiscardRestorePlan?,
    restore: (DiscardRecord) -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit) -> Unit,
) {
    val restorePlan = plan(record)

    if (restorePlan != null && restorePlan.changedSince.isNotEmpty() && restorePlan.restorable.isNotEmpty()) {
        onConfirmAction(ConfirmableAction.RestoreDiscardPartly(restorePlan.restorable, restorePlan.changedSince)) {
            restore(record)
        }
    } else {
        restore(record)
    }
}
