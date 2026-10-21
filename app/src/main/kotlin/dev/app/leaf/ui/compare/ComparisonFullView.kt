// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.repositoryopen.ComparisonAction
import dev.app.leaf.repositoryopen.ComparisonState
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.dialogs.DialogWarning
import dev.app.leaf.ui.diff.DiffPane
import dev.app.leaf.ui.getErrorText
import kotlinx.coroutines.flow.filterIsInstance
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The commits sections take at most this share of the column's height, so the files keep room. */
private const val COMMITS_MAX_SHARE = 0.4f

/**
 * A comparison in the whole tab (fork-only, `docs/fork/compare.md`, F4), as file history and the conflict resolver
 * show: both sides in full, the commits and the files on the left, and a file's diff in the rest, with A and B above
 * its sides. ↑ and ↓ step through the files. Esc, or the exit button, goes back to the log with the comparison in the
 * right pane.
 */
@Composable
fun ComparisonFullView(
    viewModel: RepositoryOpenViewModel,
    state: ComparisonState,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit, onStopAsking: () -> Unit) -> Unit,
) {
    // Remembered: a new flow at each composition started collecting again
    val diffSelected by remember(viewModel) {
        viewModel.diffSelected.filterIsInstance<DiffSelected.CommitedChanges>()
    }.collectAsState(null)
    val listStates = rememberInTab("comparisonFullViewLists", state.target) { ComparisonListStates() }
    // As wide as the right pane, where the same lists show
    val columnWidth = remember(viewModel) { viewModel.thirdPaneWidth.value.dp }

    ComparisonFullViewContent(
        state = state,
        diffSelected = diffSelected,
        listStates = listStates,
        columnWidth = columnWidth,
        subjectOf = { hash -> viewModel.logState.value.commitList[hash]?.commit?.shortMessage },
        onAction = { viewModel.onAction(it) },
        onSelectCommit = { viewModel.selectCommit(it, isStash = false, scrollToItem = true) },
        diff = { headerActions, sideLabels ->
            DiffPane(
                viewModel = viewModel,
                onCloseDiffView = {},
                onConfirmAction = onConfirmAction,
                isClosable = false,
                headerActions = headerActions,
                sideLabels = sideLabels,
            )
        },
    )
}

/**
 * The full view from its state. [diff] shows the selected file's diff, with the buttons for the files in its header and
 * the sides' labels above its lines; tests give it something lighter than the diff pane.
 */
@Composable
internal fun ComparisonFullViewContent(
    state: ComparisonState,
    diffSelected: DiffSelected.CommitedChanges?,
    listStates: ComparisonListStates,
    columnWidth: Dp,
    subjectOf: (commit: String) -> String?,
    onAction: (ComparisonAction) -> Unit,
    onSelectCommit: (Commit) -> Unit,
    diff: @Composable (headerActions: @Composable () -> Unit, sideLabels: @Composable (isSplit: Boolean) -> Unit) -> Unit,
) {
    val files = state.rows.mapNotNull { (it as? FileRow.File<DiffEntry>)?.file?.item }
    val shown = diffSelected?.items?.singleOrNull()?.diffEntry
    // By path: a comparison that was read again has new entries for the same files
    val position = if (shown == null) -1 else files.indexOfFirst { it.oldPath == shown.oldPath && it.newPath == shown.newPath }

    fun step(offset: Int) {
        val next = files.getOrNull(if (position < 0) 0 else position + offset) ?: return

        onAction(ComparisonAction.SelectEntry(next))
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .focusRequester(focusRequester)
            .focusable()
            // The file list takes the arrows itself while it has the focus
            .onKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.matchesBinding(KeybindingOption.UP) -> {
                        step(-1)
                        true
                    }

                    event.matchesBinding(KeybindingOption.DOWN) -> {
                        step(1)
                        true
                    }

                    else -> false
                }
            },
    ) {
        FullViewHeader(state, subjectOf, onAction)

        comparisonLine(state)?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.secondarySurface)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ListsColumn(
                state = state,
                diffSelected = diffSelected,
                listStates = listStates,
                onAction = onAction,
                onSelectCommit = onSelectCommit,
                modifier = Modifier.width(columnWidth).fillMaxHeight(),
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.15f)),
            )

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (shown != null) {
                    diff(
                        { FileStepper(position, files.size, onStep = ::step) },
                        { isSplit -> SideLabels(state, isSplit) },
                    )
                } else {
                    PaneNote(stringResource(Res.string.compare_full_view_pick_file))
                }
            }
        }
    }
}

/** A and B in full, with their subjects, the switch, and swap, back and end. */
@Composable
private fun FullViewHeader(
    state: ComparisonState,
    subjectOf: (commit: String) -> String?,
    onAction: (ComparisonAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The sides take the room that the switch and the buttons leave, and their subjects give way first
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FullSide(CompareLetter.A, state.target.a, state.aCommit, subjectOf(state.aCommit))

            Text(text = "→", color = MaterialTheme.colors.onBackgroundSecondary, style = MaterialTheme.typography.body2)

            FullSide(CompareLetter.B, state.target.b, state.bCommit, subjectOf(state.bCommit))
        }

        if (state.showsModeSwitch) {
            ModeSwitch(state.mode, onSelect = { onAction(ComparisonAction.SetMode(it)) })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(
                icon = Res.drawable.compare_arrows,
                description = stringResource(Res.string.compare_swap),
                onClick = { onAction(ComparisonAction.Swap) },
            )

            HeaderButton(
                icon = Res.drawable.fullscreen_exit,
                description = stringResource(Res.string.compare_leave_full_view),
                onClick = { onAction(ComparisonAction.LeaveFullView) },
            )

            HeaderButton(
                icon = Res.drawable.close,
                description = stringResource(Res.string.compare_end_full_view),
                onClick = { onAction(ComparisonAction.End) },
            )
        }
    }
}

/** A side's letter, name, commit and subject: the subject is cut first. */
@Composable
private fun RowScope.FullSide(letter: CompareLetter, side: CompareSide, commit: String, subject: String?) {
    Row(
        modifier = Modifier.weight(1f, fill = false),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SideLine(letter, side, commit)

        if (subject != null) {
            Text(
                text = subject,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The commits and the files, as in the pane, or the read's error with Try again. */
@Composable
private fun ListsColumn(
    state: ComparisonState,
    diffSelected: DiffSelected.CommitedChanges?,
    listStates: ComparisonListStates,
    onAction: (ComparisonAction) -> Unit,
    onSelectCommit: (Commit) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val commitsMaxHeight = maxHeight * COMMITS_MAX_SHARE
        val error = state.error
        val comparison = state.comparison

        Column {
            if (error != null) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DialogWarning(error.getErrorText())
                    PrimaryButton(
                        text = stringResource(Res.string.compare_try_again),
                        onClick = { onAction(ComparisonAction.TryAgain) },
                    )
                }
            } else if (comparison == null) {
                PaneNote(stringResource(Res.string.compare_reading))
            }

            if (comparison != null) {
                CommitsSections(state, listStates, onSelectCommit, commitsMaxHeight)

                SectionHeader(
                    title = stringResource(Res.string.compare_files),
                    count = comparison.changes.size,
                    action = {
                        FilesSortMenuButton(
                            viewState = state.viewState,
                            onViewStateChange = { onAction(ComparisonAction.ViewStateChanged(it)) },
                        )
                    },
                )

                if (comparison.changes.isEmpty()) {
                    PaneNote(noChangesText(state))
                } else {
                    ChangedFiles(
                        state = state,
                        diffSelected = diffSelected,
                        listState = listStates.files,
                        onOpen = { onAction(ComparisonAction.SelectEntry(it)) },
                        onAction = onAction,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** "‹ 2 of 5 ›" in the diff's header: the shown file's place among the files, and the files before and after it. */
@Composable
private fun FileStepper(position: Int, count: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            description = stringResource(Res.string.compare_previous_file),
            isEnabled = position > 0,
            rotation = 180f,
            onClick = { onStep(-1) },
        )

        Text(
            text = stringResource(Res.string.compare_file_position, position + 1, count),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )

        StepButton(
            description = stringResource(Res.string.compare_next_file),
            isEnabled = position in 0 until count - 1,
            onClick = { onStep(1) },
        )
    }
}

@Composable
private fun StepButton(description: String, isEnabled: Boolean, onClick: () -> Unit, rotation: Float = 0f) {
    DelayedTooltip(description) {
        IconButton(
            onClick = onClick,
            enabled = isEnabled,
            modifier = Modifier
                .handOnHover()
                .size(28.dp)
                .alpha(if (isEnabled) 1f else 0.35f),
        ) {
            Icon(
                painter = painterResource(Res.drawable.chevron_right),
                contentDescription = description,
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
    }
}

/** A and B above the diff's lines: over the two sides of a split diff, or on one line for a unified one. */
@Composable
private fun SideLabels(state: ComparisonState, isSplit: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(MaterialTheme.colors.secondarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSplit) {
            SideLabel(CompareLetter.A, state.aName, state.aCommit, Modifier.weight(1f))
            SideLabel(CompareLetter.B, state.bName, state.bCommit, Modifier.weight(1f))
        } else {
            SideLabel(CompareLetter.A, state.aName, state.aCommit)
            Text(text = "→", color = MaterialTheme.colors.onBackgroundSecondary, style = MaterialTheme.typography.body2)
            SideLabel(CompareLetter.B, state.bName, state.bCommit)
        }
    }
}

@Composable
private fun SideLabel(letter: CompareLetter, name: String, commit: String, modifier: Modifier = Modifier) {
    val shortCommit = commit.take(7)

    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CompareBadge(letter)

        Text(
            text = if (shortCommit.isEmpty() || name.startsWith(shortCommit)) name else "$name · $shortCommit",
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
