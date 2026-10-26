// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.rewrite.RewriteKind
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.repositoryopen.RewriteNotice
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.SecondaryButton
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The strip under the toolbar after a branch's commits were squashed or reworded (fork-only, step A3 of
 * `docs/fork/squash-and-rebase.md`): what was done and on which branch, that the upstream still has the old commits, and
 * "Push with force…", Undo and ×. It stays until it's closed or the next rewrite, as a toast would be gone before Undo
 * was found. After Undo, it says where the branch is back.
 */
@Composable
fun RewriteStrip(
    notice: RewriteNotice,
    onPushWithForce: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strip = MaterialTheme.styleColors.successStrip

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
            painter = painterResource(if (notice.isUndone) Res.drawable.undo else Res.drawable.check),
            contentDescription = null,
            tint = strip.icon,
            modifier = Modifier.size(16.dp),
        )

        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = rewriteStripText(notice),
                color = strip.content,
                style = MaterialTheme.typography.body2,
            )

            val pushedTo = notice.pushedTo

            if (pushedTo != null && !notice.isUndone) {
                Text(
                    text = stringResource(Res.string.rewrite_strip_pushed, pushedTo),
                    color = strip.content.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.body2,
                )
            }
        }

        if (!notice.isUndone) {
            if (notice.canPush) {
                SecondaryButton(
                    text = stringResource(Res.string.rewrite_strip_push),
                    colors = strip.button,
                    onClick = onPushWithForce,
                )
            }

            SecondaryButton(
                text = stringResource(Res.string.rewrite_strip_undo),
                colors = strip.button,
                onClick = onUndo,
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(32.dp)
                .handOnHover(),
        ) {
            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = stringResource(Res.string.rewrite_strip_dismiss),
                tint = strip.content,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** What was done, on which branch, or after Undo, where the branch is back. */
@Composable
internal fun rewriteStripText(notice: RewriteNotice): String {
    val branch = notice.branchName

    if (notice.isUndone) {
        return stringResource(Res.string.rewrite_strip_undone, branch, notice.result.oldTip.take(7))
    }

    return when (notice.result.kind) {
        RewriteKind.SQUASH -> when (val onto = notice.result.onto) {
            null -> pluralStringResource(Res.plurals.rewrite_strip_squashed, notice.joined, notice.joined, branch)
            else -> pluralStringResource(
                Res.plurals.rewrite_strip_squashed_onto,
                notice.joined,
                notice.joined,
                branch,
                shortName(onto.base),
            )
        }
        RewriteKind.FIXUP -> stringResource(Res.string.rewrite_strip_fixed_up, notice.commit, branch)
        RewriteKind.REWORD -> stringResource(Res.string.rewrite_strip_reworded, notice.commit, branch)
        RewriteKind.REBASE -> {
            val counts = pluralStringResource(Res.plurals.rewrite_strip_rebased_counts, notice.joined, notice.joined, notice.after)

            when (val onto = notice.result.onto) {
                null -> stringResource(Res.string.rewrite_strip_rebased, branch, counts)
                else -> stringResource(Res.string.rewrite_strip_rebased_onto, branch, shortName(onto.base), counts)
            }
        }
    }
}
