// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.isCommittedApart
import dev.app.leaf.domain.models.isCommittedApartFromAuthoring
import dev.app.leaf.domain.models.isCommittedBySomeoneElse
import dev.app.leaf.domain.models.isSamePersonAs
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.TooltipText
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.stringResource

/**
 * Fork-only: under the commit details' hash and author date, when and by whom the commit was committed, as after an
 * amend, a rebase or a cherry-pick, or a merge on a hosting site. The date ends where the author date above it does.
 * When the label and the date don't fit side by side, the date goes on a line of its own, rather than cutting either.
 * Nothing when the committer and the minute are the author's.
 */
@Composable
fun CommittedLine(
    author: Identity,
    authorDate: Long,
    committer: Identity,
    committerDate: Long,
) {
    if (!isCommittedApart(author, authorDate, committer, committerDate)) return

    val color = MaterialTheme.colors.onBackgroundSecondary
    val style = MaterialTheme.typography.body2
    val isAuthor = committer.isSamePersonAs(author)

    LabelThenDate(
        label = {
            DelayedTooltip(
                text = if (isAuthor) null else "${committer.name.orEmpty()} <${committer.email.orEmpty()}>",
            ) {
                Text(
                    text = if (isAuthor) {
                        stringResource(Res.string.commit_footer_committed)
                    } else {
                        stringResource(Res.string.commit_footer_committed_by, committer.name.orEmpty())
                    },
                    color = color,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        date = {
            TooltipText(
                text = committerDate.toSmartSystemString(allowRelative = true, showTime = true),
                // As the author date's, so that the two end together
                modifier = Modifier.padding(horizontal = 16.dp),
                color = color,
                style = style,
                maxLines = 1,
                tooltipTitle = committerDate.toSmartSystemString(allowRelative = false, showTime = true),
            )
        },
    )
}

/**
 * Fork-only: the tooltip of a commit's author date where a list shows it, as file history and the commit editor do: the
 * whole date, and when the commit was committed at another minute or by someone else, when and by whom too.
 */
@Composable
fun authorDateTooltip(commit: Commit): String {
    val authored = commit.authorDate.toSmartSystemString(allowRelative = false, showTime = true)

    if (!commit.isCommittedApartFromAuthoring) return authored

    val committed = commit.date.toSmartSystemString(allowRelative = false, showTime = true)
    val committedLine = if (commit.isCommittedBySomeoneElse) {
        stringResource(
            Res.string.log_column_author_committed_by,
            commit.committer.name.orEmpty(),
            commit.committer.email.orEmpty(),
            committed,
        )
    } else {
        stringResource(Res.string.log_column_date_committed, committed)
    }

    return stringResource(Res.string.log_column_date_authored, authored) + "\n" + committedLine
}

/** [label] at the start and [date] at the end of one line, or [date] at the end of a second line when they don't fit. */
@Composable
private fun LabelThenDate(label: @Composable () -> Unit, date: @Composable () -> Unit) {
    Layout(contents = listOf(label, date)) { (labelMeasurables, dateMeasurables), constraints ->
        val width = constraints.maxWidth
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val datePlaceable = dateMeasurables.single().measure(free)
        val labelMeasurable = labelMeasurables.single()
        val fitsBeside = labelMeasurable.maxIntrinsicWidth(free.maxHeight) + datePlaceable.width <= width

        val labelPlaceable = labelMeasurable.measure(
            free.copy(maxWidth = if (fitsBeside) width - datePlaceable.width else width),
        )

        if (fitsBeside) {
            val height = maxOf(labelPlaceable.height, datePlaceable.height)

            layout(width, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                datePlaceable.place(width - datePlaceable.width, (height - datePlaceable.height) / 2)
            }
        } else {
            layout(width, labelPlaceable.height + datePlaceable.height) {
                labelPlaceable.place(0, 0)
                datePlaceable.place(width - datePlaceable.width, labelPlaceable.height)
            }
        }
    }
}
