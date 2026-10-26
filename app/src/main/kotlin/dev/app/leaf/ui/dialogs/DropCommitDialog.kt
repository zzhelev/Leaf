// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanPlace
import dev.app.leaf.domain.rewrite.PlanStart
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.planFooter
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.repositoryopen.PlanFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.commiteditor.AutostashBox
import dev.app.leaf.ui.commiteditor.PlanNotes
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 460.dp

/** How many of the commit's files the dialog lists. */
private const val FILES_LISTED = 8

/** What the dialog's check found: the check of the plan, and where it would replay if it stops. */
class DropCheck(val check: PlanCheck, val place: PlanPlace)

/**
 * "Drop commit…" on a commit row (fork-only, choice 4a of `docs/fork/commit-editor.md`): the commit, its files, and,
 * once the editor's check has run on the plan that drops it, where the commits after it stop if they do. "Drop
 * commit" rewrites them without a worktree, with Undo in the strip; "Drop and rebase" replays them with
 * `git rebase -i`. The editor's footer notes say the rest: where it runs, what is in the way.
 *
 * @param plan the plan from the commit, which drops it.
 * @param check the check of [plan], run when the dialog opens.
 * @param filesOf the commit's files, for the list.
 */
@Composable
fun DropCommitDialog(
    plan: RebasePlan,
    check: suspend () -> DropCheck,
    filesOf: suspend () -> List<PlanFile>?,
    onConfirm: (check: PlanCheck, autostash: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val commit = plan.rows.first().commit
    var checked by remember { mutableStateOf<DropCheck?>(null) }
    var files by remember { mutableStateOf<List<PlanFile>?>(null) }
    var autostash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { checked = check() }
    LaunchedEffect(Unit) { files = filesOf() }

    val footer = checked?.let { planFooter(plan, it.check, it.place, autostash) }
    val canConfirm = footer?.canStart == true
    val confirm = {
        val found = checked

        if (canConfirm && found != null) {
            onConfirm(found.check, autostash)
            onDismiss()
        }
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.delete),
        title = stringResource(Res.string.drop_commit_dialog_title, commit.shortHash),
        subtitle = commit.shortMessage,
        primaryActionText = if (footer?.start == PlanStart.REBASE) {
            stringResource(Res.string.drop_commit_dialog_drop_and_rebase)
        } else {
            stringResource(Res.string.drop_commit_dialog_drop)
        },
        primaryActionColors = MaterialTheme.styleColors.destructive,
        isPrimaryActionEnabled = canConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
        onDismiss = onDismiss,
        onPrimaryActionClicked = confirm,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            files?.takeIf { it.isNotEmpty() }?.let { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (file in shown.take(FILES_LISTED)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = file.path,
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }

                    if (shown.size > FILES_LISTED) {
                        Text(
                            text = "+${shown.size - FILES_LISTED}",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                        )
                    }
                }
            }

            if (footer == null) {
                Text(
                    text = stringResource(Res.string.drop_commit_dialog_checking),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            } else {
                if (footer.notes.isNotEmpty()) {
                    PlanNotes(footer.notes, branch = shortName(plan.line.branch))
                }

                footer.autostashCount?.let { count ->
                    AutostashBox(count, autostash, onCheckedChange = { autostash = it })
                }
            }
        }
    }
}
