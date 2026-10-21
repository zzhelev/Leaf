// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.RECENT_CHANGE_MS
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.shellLines
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.scrollbarHover
import dev.app.leaf.theme.scrollbarNormal
import dev.app.leaf.theme.roleBorder
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.styleColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * The commands that a worktree action runs (fork-only), as lines that can be copied into a terminal: `cd` to the
 * folder, then the git commands. A step that isn't a command, such as switching the tab, is a comment. With a
 * [maxHeight], the lines scroll past it, inside their box.
 */
@Composable
fun WorktreeCommands(steps: List<WorktreeStep>, modifier: Modifier = Modifier, maxHeight: Dp = Dp.Unspecified) {
    val home = System.getProperty("user.home")
    val comments = steps.filter { it !is WorktreeStep.Git }.associateWith { step ->
        when (step) {
            is WorktreeStep.SwitchTab -> if (step.onlyIfStoppedAtConflicts) {
                stringResource(Res.string.worktree_dialog_switch_tab_if_conflicts, File(step.folder).name)
            } else {
                stringResource(Res.string.worktree_dialog_switch_tab, File(step.folder).name)
            }

            is WorktreeStep.CopyFiles -> pluralStringResource(
                Res.plurals.worktree_dialog_copy_files,
                step.files.size,
                step.files.size,
                File(step.from).name,
                fileList(step.files),
            )

            is WorktreeStep.WriteSquashMessage -> stringResource(Res.string.worktree_dialog_write_squash_message)
            is WorktreeStep.Git -> ""
        }
    }
    val lines = shellLines(steps, home) { comments.getValue(it) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.worktree_dialog_commands),
            color = MaterialTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.caption,
        )

        SelectionContainer {
            val text = lines.joinToString("\n")

            if (maxHeight == Dp.Unspecified) {
                CommandLines(
                    text = text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colors.secondarySurface)
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                )
            } else {
                // The scrollbar goes inside the box, which keeps its width
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colors.secondarySurface),
                ) {
                    ScrollingBox(maxHeight = maxHeight) {
                        CommandLines(text = text, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandLines(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colors.onBackground,
        style = MaterialTheme.typography.body2,
        fontFamily = monoTypography(),
    )
}

/** The room that a [ScrollingBox] leaves at the right of its content for the scrollbar. */
private val SCROLLBAR_ROOM = 14.dp

/** How far inside a [ScrollingBox]'s right edge, top and bottom its scrollbar is. */
private val SCROLLBAR_INSET = 3.dp

/**
 * [content] as wide as the box, and as tall as it is up to [maxHeight], then scrolling, with a scrollbar inside its
 * right edge that shows it scrolls (only then). The content leaves room for it, so that it covers nothing. Every
 * scrolling part of a worktree dialog has it in the same place, so that their scrollbars line up.
 *
 * Leaf's `ScrollableColumn` fills the height it's given, which suits a pane, not a list in a dialog.
 */
@Composable
internal fun ScrollingBox(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()

    Layout(
        content = {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(end = SCROLLBAR_ROOM),
                verticalArrangement = verticalArrangement,
                content = content,
            )

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                style = LocalScrollbarStyle.current.copy(
                    unhoverColor = MaterialTheme.colors.scrollbarNormal,
                    hoverColor = MaterialTheme.colors.scrollbarHover,
                ),
            )
        },
        modifier = modifier.heightIn(max = maxHeight),
        measurePolicy = ScrollingBoxMeasurePolicy,
    )
}

/**
 * Lays out [ScrollingBox]: the content as wide as the box and as tall as it is, up to the box's limit, and the scrollbar
 * inside its right edge, top and bottom. The box's intrinsic sizes are the content's: the scrollbar can't answer them,
 * as it measures itself with the height it's given, and the dialogs ask for their content's intrinsic width.
 */
private object ScrollingBoxMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val content = measurables[0].measure(constraints.copy(minWidth = width))
        val inset = SCROLLBAR_INSET.roundToPx()
        val scrollbarHeight = (content.height - 2 * inset).coerceAtLeast(0)
        val scrollbar = measurables[1].measure(
            Constraints(maxWidth = content.width, minHeight = scrollbarHeight, maxHeight = scrollbarHeight)
        )

        return layout(content.width, content.height) {
            content.place(0, 0)
            scrollbar.place(content.width - scrollbar.width - inset, inset)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables[0].maxIntrinsicHeight(width)
}

/** At most [MAX_FILES_SHOWN] of [files], then "…" when there are more. */
internal fun fileList(files: List<String>): String {
    val shown = files.take(MAX_FILES_SHOWN).joinToString(", ")

    return if (files.size > MAX_FILES_SHOWN) "$shown, …" else shown
}

private const val MAX_FILES_SHOWN = 5

/**
 * Something the user should know before confirming that doesn't lose work by itself, such as an agent that may still
 * be working in the worktree. Its text can be selected and copied.
 */
@Composable
fun DialogNotice(text: String) {
    val strip = MaterialTheme.styleColors.warningStrip
    val shape = RoundedCornerShape(4.dp)

    SelectionContainer {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(strip.background)
                .roleBorder(strip.border, shape)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            color = strip.content,
            style = MaterialTheme.typography.body2,
        )
    }
}

/** A worktree that changed this recently may have an agent working in it. */
private val RECENT_CHANGE = RECENT_CHANGE_MS.milliseconds

/**
 * Says that the worktree [worktreeName] changed in the last few minutes, at [lastChange] (milliseconds since the
 * epoch), so an agent may still be working in it. Nothing when it didn't, or [lastChange] is null.
 */
@Composable
fun RecentChangeNotice(worktreeName: String, lastChange: Long?) {
    if (lastChange == null) {
        return
    }

    val sinceChange = (System.currentTimeMillis() - lastChange).coerceAtLeast(0).milliseconds

    if (sinceChange < RECENT_CHANGE) {
        val minutes = sinceChange.inWholeMinutes.toInt()

        DialogNotice(
            if (minutes == 0) {
                stringResource(Res.string.worktree_dialog_recent_change_now, worktreeName)
            } else {
                pluralStringResource(Res.plurals.worktree_dialog_recent_change, minutes, worktreeName, minutes)
            }
        )
    }
}

/** A secondary line of a dialog, such as whether a branch is merged. */
@Composable
fun DialogNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colors.onBackgroundSecondary,
        style = MaterialTheme.typography.body2,
    )
}
