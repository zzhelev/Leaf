// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.shellLines
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Dark text on the conflict color, which is the same amber in the light and the dark theme. */
private val OnNoticeColor = Color(0xFF1E1E1E)

/**
 * The commands that a worktree action runs (fork-only), as lines that can be copied into a terminal: `cd` to the
 * folder, then the git commands. A step that isn't a command, such as switching the tab, is a comment.
 */
@Composable
fun WorktreeCommands(steps: List<WorktreeStep>, modifier: Modifier = Modifier) {
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
            Text(
                text = lines.joinToString("\n"),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.secondarySurface)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                fontFamily = monoTypography(),
            )
        }
    }
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
    SelectionContainer {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.conflictFile)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            color = OnNoticeColor,
            style = MaterialTheme.typography.body2,
        )
    }
}

/** A worktree that changed this recently may have an agent working in it. */
private val RECENT_CHANGE = 5.minutes

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
