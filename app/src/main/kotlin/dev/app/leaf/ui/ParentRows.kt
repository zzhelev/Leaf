// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.log.ParentLink
import dev.app.leaf.domain.usecases.LoadLogResult
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.repositoryopen.CommitParentsState
import dev.app.leaf.repositoryopen.ParentJump
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.parentContextMenu
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val PARENT_ROW_HEIGHT = 22.dp

/**
 * More parents than this, expanded, scroll: an octopus merge can have dozens. Seven and a half rows, so that a row cut
 * in half at the bottom shows that there are more.
 */
private const val MAX_ROWS_BEFORE_SCROLL = 7.5f

/**
 * A commit's parents in its details, one row each (fork-only, upstream issue 270): an arrow, the parent's number when
 * the commit is a merge, its short hash and its subject. A click selects the parent in the log, and the row's menu
 * copies its hash. A root commit says that it has no parent.
 *
 * A commit with more parents than fit shows the first ones and "Show N more parents", which opens the rest in place.
 */
@Composable
fun ParentRows(
    state: CommitParentsState,
    onParentClicked: (hash: String) -> Unit,
    onToggleMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = state.links

    Column(modifier) {
        if (links.isRoot) {
            Row(Modifier.height(PARENT_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.commit_parents_root),
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            return@Column
        }

        val rows = if (state.isExpanded) links.all else links.shown
        val rowsModifier = if (state.isExpanded) {
            Modifier
                .heightIn(max = PARENT_ROW_HEIGHT * MAX_ROWS_BEFORE_SCROLL)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
        }

        Column(rowsModifier) {
            rows.forEach { link ->
                ParentRow(
                    link = link,
                    isNumbered = links.isNumbered,
                    jump = state.jump.takeIf { it.isAbout(link.hash) } ?: ParentJump.None,
                    onClick = { onParentClicked(link.hash) },
                )
            }
        }

        if (links.hidden.isNotEmpty()) {
            MoreParentsRow(
                hiddenCount = links.hidden.size,
                hiddenHashes = links.hidden.map { it.shortHash },
                isExpanded = state.isExpanded,
                onClick = onToggleMore,
            )
        }
    }
}

private fun ParentJump.isAbout(hash: String) = when (this) {
    ParentJump.None -> false
    is ParentJump.Looking -> this.hash == hash
    is ParentJump.NotFound -> this.hash == hash
}

@Composable
private fun ParentRow(
    link: ParentLink,
    isNumbered: Boolean,
    jump: ParentJump,
    onClick: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val linkColor = MaterialTheme.colors.primaryVariant
    val mutedColor = MaterialTheme.colors.onBackgroundSecondary
    val amber = MaterialTheme.colors.amberText

    ContextMenu(
        items = {
            parentContextMenu(
                onSelectInLog = onClick,
                onCopyCommitHash = { scope.launch { clipboard.setClipboardText(link.hash) } },
            )
        },
    ) {
        DelayedTooltip(text = parentTooltip(link, isNumbered, jump)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PARENT_ROW_HEIGHT)
                    .handMouseClickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (jump) {
                    is ParentJump.Looking -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = linkColor,
                    )

                    is ParentJump.NotFound -> Icon(
                        painter = painterResource(Res.drawable.warning),
                        contentDescription = null,
                        tint = amber,
                        modifier = Modifier.size(14.dp),
                    )

                    ParentJump.None -> Icon(
                        painter = painterResource(Res.drawable.arrow_upward),
                        contentDescription = null,
                        tint = linkColor,
                        modifier = Modifier.size(14.dp),
                    )
                }

                if (isNumbered) {
                    Text(
                        text = link.position.toString(),
                        color = mutedColor,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.width(8.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Text(
                    text = link.shortHash,
                    color = linkColor,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    softWrap = false,
                )

                val detail = parentDetail(link, jump)

                Text(
                    text = detail.text,
                    color = if (detail.isProblem) amber else mutedColor,
                    fontStyle = if (detail.isItalic) FontStyle.Italic else null,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

private class ParentDetail(val text: String, val isItalic: Boolean = false, val isProblem: Boolean = false)

/** What a row says after the hash: the subject, or why there is none, or what became of a click. */
@Composable
private fun parentDetail(link: ParentLink, jump: ParentJump): ParentDetail = when (jump) {
    is ParentJump.Looking -> ParentDetail(stringResource(Res.string.commit_parents_looking), isItalic = true)

    is ParentJump.NotFound -> ParentDetail(
        text = when (val result = jump.result) {
            LoadLogResult.NotReachable -> stringResource(Res.string.commit_parents_not_reachable)
            is LoadLogResult.LimitReached -> stringResource(Res.string.commit_parents_limit_reached, "%,d".format(result.loaded))
            else -> stringResource(Res.string.commit_parents_unavailable)
        },
        isProblem = true,
    )

    ParentJump.None -> when {
        !link.isLoaded -> ParentDetail(stringResource(Res.string.commit_parents_not_loaded), isItalic = true)
        else -> ParentDetail(link.subject.orEmpty())
    }
}

/** The tooltip of a row: which parent it is in a merge, its message, who and when, and the hash. */
@Composable
private fun parentTooltip(link: ParentLink, isNumbered: Boolean, jump: ParentJump): String {
    val position = when {
        link.position == 1 -> stringResource(Res.string.commit_parents_tooltip_first, link.position)
        else -> stringResource(Res.string.commit_parents_tooltip_other, link.position)
    }
    val commit = link.commit

    val body = when {
        jump is ParentJump.Looking -> stringResource(Res.string.commit_parents_looking_tooltip, "%,d".format(jump.loaded))
        jump is ParentJump.NotFound -> when (val result = jump.result) {
            LoadLogResult.NotReachable -> stringResource(Res.string.commit_parents_tooltip_not_reachable)
            is LoadLogResult.LimitReached ->
                stringResource(Res.string.commit_parents_tooltip_limit_reached, "%,d".format(result.loaded))

            else -> stringResource(Res.string.commit_parents_tooltip_unavailable)
        }

        commit == null -> stringResource(Res.string.commit_parents_tooltip_not_loaded)
        else -> listOf(
            commit.message.forTooltip(maxLines = 8),
            "${commit.author.name.orEmpty()} · ${commit.date.toSmartSystemString(allowRelative = false, showTime = true)}",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    // Only a merge's parents are told apart: with one parent, "parent 1" says nothing
    return listOfNotNull(position.takeIf { isNumbered }, body, link.hash).joinToString("\n")
}

@Composable
private fun MoreParentsRow(
    hiddenCount: Int,
    hiddenHashes: List<String>,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val linkColor = MaterialTheme.colors.primaryVariant
    val tooltip = if (isExpanded) null else hiddenHashes.joinToString(", ")

    DelayedTooltip(text = tooltip) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PARENT_ROW_HEIGHT)
                .handMouseClickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.expand_more),
                contentDescription = null,
                tint = linkColor,
                modifier = Modifier.size(14.dp).rotate(if (isExpanded) 180f else 0f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isExpanded) {
                    stringResource(Res.string.commit_parents_show_fewer)
                } else {
                    stringResource(Res.string.commit_parents_show_more, hiddenCount)
                },
                color = linkColor,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
