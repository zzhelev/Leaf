// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.icon
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.repositoryopen.ComparisonAction
import dev.app.leaf.repositoryopen.ComparisonState
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.repositoryopen.ShownCommit
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.ChangedFilesList
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.TitleWithTrailingAction
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.parentContextMenu
import dev.app.leaf.ui.dialogs.DialogWarning
import dev.app.leaf.ui.forTooltip
import dev.app.leaf.ui.getErrorText
import dev.app.leaf.ui.keepInView
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The commits sections take at most this share of the pane's height, so the files keep room. */
private const val COMMITS_MAX_SHARE = 0.4f

private val SECTION_HEADER_HEIGHT = 34.dp
private val COMMIT_ROW_HEIGHT = 28.dp

/** Where the pane's lists are scrolled to: B's commits, A's commits and the files. */
class ComparisonListStates {
    val commitsB = LazyListState()
    val commitsA = LazyListState()
    val files = LazyListState()
}

/**
 * A comparison of two commits or branches, or the review of a worktree's changes (fork-only, `docs/fork/compare.md`,
 * F3 and F7), in place of Files changed: the two sides, the mode, the commits that each has alone and the files. A file
 * opens in the diff pane, as a commit's file does.
 */
@Composable
fun ComparisonPane(
    viewModel: RepositoryOpenViewModel,
    state: ComparisonState,
) {
    // Remembered: a new flow at each composition started collecting again
    val diffSelected by remember(viewModel) {
        viewModel.diffSelected.filterIsInstance<DiffSelected.CommitedChanges>()
    }.collectAsState(null)

    // Kept with the tab, as a commit's lists are, and started again for other sides
    val listStates = rememberInTab("comparisonLists", state.target) { ComparisonListStates() }

    ComparisonPaneView(
        state = state,
        diffSelected = diffSelected,
        listStates = listStates,
        onAction = { viewModel.onAction(it) },
        onSelectCommit = { viewModel.selectCommit(it, isStash = false, scrollToItem = true) },
    )
}

@Composable
internal fun ComparisonPaneView(
    state: ComparisonState,
    diffSelected: DiffSelected.CommitedChanges?,
    listStates: ComparisonListStates,
    onAction: (ComparisonAction) -> Unit,
    onSelectCommit: (Commit) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .fillMaxSize(),
    ) {
        ComparisonHeader(state, onAction)

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colors.background),
        ) {
            val commitsMaxHeight = maxHeight * COMMITS_MAX_SHARE

            Column {
                val error = state.error
                val comparison = state.comparison

                ModeOrNote(state, onAction)

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
                    CommitsSections(state, listStates, onSelectCommit, onAction, commitsMaxHeight)

                    FilesSection(
                        state = state,
                        diffSelected = diffSelected,
                        listStates = listStates,
                        onOpen = { onAction(ComparisonAction.OpenFullView(it)) },
                        onAction = onAction,
                    )
                }
            }
        }

        UncommittedChangesLine(state, onShow = { onAction(ComparisonAction.ShowUncommittedChanges) })
    }
}

/** A and B, each on a line, then swap and end. */
@Composable
private fun ComparisonHeader(state: ComparisonState, onAction: (ComparisonAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SideLine(CompareLetter.A, state.target.a, state.aCommit)
            SideLine(CompareLetter.B, state.target.b, state.bCommit)
        }

        HeaderButton(
            icon = Res.drawable.compare_arrows,
            description = stringResource(Res.string.compare_swap),
            rotation = 90f,
            onClick = { onAction(ComparisonAction.Swap) },
        )

        HeaderButton(
            icon = Res.drawable.fullscreen,
            description = stringResource(Res.string.compare_open_full_view),
            onClick = { onAction(ComparisonAction.OpenFullView()) },
        )

        HeaderButton(
            icon = Res.drawable.close,
            description = stringResource(Res.string.compare_end),
            onClick = { onAction(ComparisonAction.End) },
        )
    }
}

/** A side's letter, its name and the commit it's at, which a side named by its commit doesn't repeat. */
@Composable
internal fun SideLine(letter: CompareLetter, side: CompareSide, commit: String) {
    Row(
        // Read as one: "A main 5d5537f"
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CompareBadge(letter)

        Text(
            text = side.name,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        val shortCommit = commit.take(7)

        if (shortCommit.isNotEmpty() && !side.name.startsWith(shortCommit)) {
            Text(
                text = shortCommit,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun HeaderButton(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    rotation: Float = 0f,
) {
    DelayedTooltip(description) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .handOnHover()
                .size(32.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        }
    }
}

/**
 * The switch between the two modes and what the mode shows, for sides that diverged, or else a line that says how the
 * two meet.
 */
@Composable
private fun ModeOrNote(state: ComparisonState, onAction: (ComparisonAction) -> Unit) {
    val line = comparisonLine(state) ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (state.showsModeSwitch) {
            ModeSwitch(state.mode, onSelect = { onAction(ComparisonAction.SetMode(it)) })
        }

        Text(
            text = line,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )
    }
}

/** What the shown mode leaves out, for sides that diverged, or else how the two histories meet. */
@Composable
internal fun comparisonLine(state: ComparisonState): String? {
    val comparison = state.comparison ?: return null
    val a = state.aName
    val b = state.bName

    return if (state.showsModeSwitch) {
        val newer = comparison.countOnlyInA

        when (state.mode) {
            ComparisonMode.SINCE_SPLIT -> pluralStringResource(
                Res.plurals.compare_mode_since_split_line,
                newer,
                b,
                a,
                comparison.mergeBase.orEmpty().take(7),
                newer,
            )

            ComparisonMode.ALL -> pluralStringResource(Res.plurals.compare_mode_all_line, newer, a, newer, b)
        }
    } else {
        when (comparison.relation) {
            ComparisonRelation.SAME -> stringResource(Res.string.compare_note_same, a, b)
            ComparisonRelation.A_IS_ANCESTOR -> pluralStringResource(
                Res.plurals.compare_note_a_is_ancestor,
                comparison.countOnlyInB,
                b,
                a,
                comparison.countOnlyInB,
            )

            ComparisonRelation.B_IS_ANCESTOR -> if (comparison.mode == ComparisonMode.ALL) {
                pluralStringResource(Res.plurals.compare_note_b_is_ancestor, comparison.countOnlyInA, a, b, comparison.countOnlyInA)
            } else {
                stringResource(Res.string.compare_note_brings_nothing, b, a)
            }

            ComparisonRelation.UNRELATED -> if (comparison.mode == ComparisonMode.ALL) {
                stringResource(Res.string.compare_note_unrelated, a, b)
            } else {
                stringResource(Res.string.compare_note_no_merge_base, b, a)
            }

            ComparisonRelation.DIVERGED -> null
        }
    }
}

/** "Since they split" and "All differences", side by side, the shown one filled. */
@Composable
internal fun ModeSwitch(mode: ComparisonMode, onSelect: (ComparisonMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
    ) {
        for (option in ComparisonMode.entries) {
            val isSelected = option == mode

            Text(
                text = when (option) {
                    ComparisonMode.SINCE_SPLIT -> stringResource(Res.string.compare_mode_since_split)
                    ComparisonMode.ALL -> stringResource(Res.string.compare_mode_all)
                },
                style = MaterialTheme.typography.caption,
                color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                    }
                    .background(if (isSelected) MaterialTheme.colors.primary else Color.Transparent)
                    .handMouseClickable { if (!isSelected) onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The files: the shown commit's strip, the header with its count and the view's button, then the list, or what an
 * empty list means, or the commit's read that failed. [onOpen] is a file's click.
 */
@Composable
internal fun ColumnScope.FilesSection(
    state: ComparisonState,
    diffSelected: DiffSelected.CommitedChanges?,
    listStates: ComparisonListStates,
    onOpen: (DiffEntry) -> Unit,
    onAction: (ComparisonAction) -> Unit,
) {
    val shownCommit = state.shownCommit

    if (shownCommit != null) {
        ShownCommitStrip(shownCommit, onAction)
    }

    SectionHeader(
        title = stringResource(Res.string.compare_files),
        count = state.files.size,
        action = {
            FilesSortMenuButton(
                viewState = state.viewState,
                onViewStateChange = { onAction(ComparisonAction.ViewStateChanged(it)) },
            )
        },
    )

    val commitError = shownCommit?.error

    when {
        commitError != null -> Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialogWarning(commitError.getErrorText())
            PrimaryButton(
                text = stringResource(Res.string.compare_try_again),
                onClick = { onAction(ComparisonAction.TryAgain) },
            )
        }

        state.files.isEmpty() -> if (shownCommit == null || !shownCommit.isLoading) {
            PaneNote(noChangesText(state))
        }

        else -> ChangedFiles(
            state = state,
            diffSelected = diffSelected,
            listState = listStates.files,
            onOpen = onOpen,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The commit whose own changes the files are (`docs/fork/compare.md`, 7.4): "Changes of e701c86 · 2 of 5", buttons to
 * the older and the newer of its side's commits, and "All changes", which lists the comparison's again.
 */
@Composable
internal fun ShownCommitStrip(shown: ShownCommit, onAction: (ComparisonAction) -> Unit) {
    val commit = shown.commit
    val mergeNote = stringResource(Res.string.compare_commit_merge_note)
    // The whole message, and for a merge which of its parents the changes are against
    val tooltip = remember(commit.message, commit.parentCount, mergeNote) {
        val message = commit.message.forTooltip()

        if (commit.parentCount > 1) "$message\n\n$mergeNote" else message
    }

    Row(
        modifier = Modifier
            .padding(top = 1.dp)
            .height(SECTION_HEADER_HEIGHT)
            .fillMaxWidth()
            .background(MaterialTheme.colors.secondarySurface)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Changes of" gives way first, the commit's id and place stay, the arrows follow them, and All changes keeps
        // the end. All of it fits the pane's default 330 px
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            DelayedTooltip(tooltip, modifier = Modifier.weight(1f, fill = false)) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.compare_commit_changes_of),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                    )

                    Text(
                        text = commit.shortHash,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                    )

                    if (shown.count > 0) {
                        Text(
                            text = stringResource(Res.string.compare_commit_position, shown.position, shown.count),
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }

            StepButton(
                description = stringResource(Res.string.compare_older_commit),
                isEnabled = shown.position > 1,
                rotation = 180f,
                onClick = { onAction(ComparisonAction.StepCommit(-1)) },
                size = 24.dp,
                modifier = Modifier.padding(start = 2.dp),
            )

            StepButton(
                description = stringResource(Res.string.compare_newer_commit),
                isEnabled = shown.position in 1 until shown.count,
                onClick = { onAction(ComparisonAction.StepCommit(1)) },
                size = 24.dp,
            )
        }

        Text(
            text = stringResource(Res.string.compare_all_changes),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.primaryVariant,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 6.dp)
                .handMouseClickable { onAction(ComparisonAction.ShowAllChanges) },
        )
    }
}

@Composable
internal fun StepButton(
    description: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    rotation: Float = 0f,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    DelayedTooltip(description) {
        IconButton(
            onClick = onClick,
            enabled = isEnabled,
            modifier = modifier
                .handOnHover()
                .size(size)
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

/**
 * The commits that each side has alone, each in a section that opens and closes: B's open, A's closed. A click on a
 * commit lists its own changes in the files (7.4), and a second click lists all of them again.
 */
@Composable
internal fun CommitsSections(
    state: ComparisonState,
    listStates: ComparisonListStates,
    onSelectCommit: (Commit) -> Unit,
    onAction: (ComparisonAction) -> Unit,
    maxHeight: Dp,
) {
    val comparison = state.comparison ?: return
    val hasB = comparison.countOnlyInB > 0
    val hasA = comparison.countOnlyInA > 0
    var isBOpen by remember(state.target, hasB) { mutableStateOf(hasB) }
    var isAOpen by remember(state.target, hasB) { mutableStateOf(!hasB) }

    if (hasB) {
        SectionHeader(
            title = if (comparison.relation == ComparisonRelation.A_IS_ANCESTOR) {
                stringResource(Res.string.compare_commits_from_a_to_b)
            } else {
                stringResource(Res.string.compare_commits_only_in_b)
            },
            count = comparison.countOnlyInB,
            isOpen = isBOpen,
            onToggle = { isBOpen = !isBOpen },
        )

        if (isBOpen) {
            CommitsList(
                commits = comparison.commitsOnlyInB,
                count = comparison.countOnlyInB,
                listState = listStates.commitsB,
                shownCommit = state.shownCommit?.commit?.hash,
                onSelectCommit = onSelectCommit,
                onShowCommit = { onAction(ComparisonAction.ShowCommit(it)) },
                modifier = Modifier.heightIn(max = maxHeight),
            )
        }
    }

    if (hasA) {
        SectionHeader(
            title = stringResource(Res.string.compare_commits_only_in_a),
            count = comparison.countOnlyInA,
            isOpen = isAOpen,
            onToggle = { isAOpen = !isAOpen },
        )

        if (isAOpen) {
            CommitsList(
                commits = comparison.commitsOnlyInA,
                count = comparison.countOnlyInA,
                listState = listStates.commitsA,
                shownCommit = state.shownCommit?.commit?.hash,
                onSelectCommit = onSelectCommit,
                onShowCommit = { onAction(ComparisonAction.ShowCommit(it)) },
                modifier = Modifier.heightIn(max = maxHeight),
            )
        }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    count: Int,
    isOpen: Boolean? = null,
    onToggle: () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .padding(top = 1.dp)
            .height(SECTION_HEADER_HEIGHT)
            .fillMaxWidth()
            .background(MaterialTheme.colors.tertiarySurface)
            .run { if (isOpen != null) handMouseClickable(onClick = onToggle) else this },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun Title() {
            Row(
                modifier = Modifier.padding(start = if (isOpen != null) 8.dp else 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOpen != null) {
                    Icon(
                        painter = painterResource(if (isOpen) Res.drawable.expand_more else Res.drawable.chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colors.onBackgroundSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                )

                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                )
            }
        }

        if (action != null) {
            TitleWithTrailingAction(
                title = { Title() },
                action = action,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        } else {
            Title()
        }
    }
}

/**
 * A side's commits, oldest first, by subject, with a note for the older ones that aren't listed. The commit whose
 * changes the files show ([shownCommit]) is highlighted, and kept in view as ‹ › step through them.
 */
@Composable
private fun CommitsList(
    commits: List<Commit>,
    count: Int,
    listState: LazyListState,
    shownCommit: String?,
    onSelectCommit: (Commit) -> Unit,
    onShowCommit: (Commit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val older = count - commits.size
    val shownIndex = commits.indexOfFirst { it.hash == shownCommit }

    LaunchedEffect(shownCommit, shownIndex) {
        if (shownIndex >= 0) {
            listState.keepInView(shownIndex + if (older > 0) 1 else 0)
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        if (older > 0) {
            item(key = "older") {
                PaneNote(pluralStringResource(Res.plurals.compare_older_commits, older, older))
            }
        }

        items(commits, key = { it.hash }) { commit ->
            CommitRow(commit, isShown = commit.hash == shownCommit, onSelectCommit, onShowCommit)
        }
    }
}

@Composable
private fun CommitRow(
    commit: Commit,
    isShown: Boolean,
    onSelectCommit: (Commit) -> Unit,
    onShowCommit: (Commit) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    ContextMenu(
        items = {
            parentContextMenu(
                onSelectInLog = { onSelectCommit(commit) },
                onCopyCommitHash = { scope.launch { clipboard.setClipboardText(commit.hash) } },
            )
        },
    ) {
        DelayedTooltip(commit.message.trim()) {
            Row(
                modifier = Modifier
                    .height(COMMIT_ROW_HEIGHT)
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { selected = isShown }
                    .background(if (isShown) MaterialTheme.colors.backgroundSelected else Color.Transparent)
                    .handMouseClickable { onShowCommit(commit) }
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommitMark(modifier = Modifier.padding(end = 8.dp))

                Text(
                    text = commit.shortMessage,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = commit.shortHash,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** A commit, drawn as git's commit symbol: a ring on a line. */
@Composable
internal fun CommitMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colors.onBackgroundSecondary

    Canvas(modifier = modifier.size(14.dp)) {
        val radius = size.minDimension / 4
        val stroke = 1.5.dp.toPx()

        drawLine(color, Offset(0f, center.y), Offset(center.x - radius, center.y), stroke)
        drawLine(color, Offset(center.x + radius, center.y), Offset(size.width, center.y), stroke)
        drawCircle(color, radius, center, style = Stroke(stroke))
    }
}

/** The files: [onOpen] for a click, and the keyboard shows a file's diff as it moves. */
@Composable
internal fun ChangedFiles(
    state: ComparisonState,
    diffSelected: DiffSelected.CommitedChanges?,
    listState: LazyListState,
    onOpen: (DiffEntry) -> Unit,
    onAction: (ComparisonAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChangedFilesList(
        rows = state.rows,
        viewState = state.viewState,
        selectedEntries = diffSelected?.items?.map { it.diffEntry }.orEmpty(),
        listState = listState,
        resetKey = state.target,
        fileIcon = { it.icon },
        fileIconColor = { it.iconColor },
        onFileClick = onOpen,
        onKeyboardSelect = { onAction(ComparisonAction.SelectEntry(it)) },
        onFolderToggle = { onAction(ComparisonAction.TreeDirectoryToggle(it)) },
        onSplitRatioChange = { onAction(ComparisonAction.ViewStateChanged(state.viewState.copy(splitRatio = it))) },
        onGenerateContextMenu = { emptyList() },
        modifier = modifier,
    )
}

/** What an empty file list says: the review's question is what a merge would change. */
@Composable
internal fun noChangesText(state: ComparisonState): String = when {
    state.shownCommit != null -> stringResource(Res.string.compare_commit_no_changes)
    state.review != null -> stringResource(Res.string.compare_review_no_changes)
    else -> stringResource(Res.string.compare_no_changes)
}

/** The reviewed worktree's uncommitted changes, which a merge leaves out, and a link that shows them. */
@Composable
private fun UncommittedChangesLine(state: ComparisonState, onShow: () -> Unit) {
    val review = state.review ?: return
    val count = review.uncommittedChanges ?: return

    if (count == 0) return

    val canShow = review.isTabWorktree || review.canSwitchTo

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (review.isTabWorktree) {
                pluralStringResource(Res.plurals.worktree_review_uncommitted_here, count, count)
            } else {
                pluralStringResource(Res.plurals.worktree_review_uncommitted, count, count, review.name)
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (canShow) {
            Text(
                text = if (review.isTabWorktree) {
                    stringResource(Res.string.worktree_review_show_uncommitted)
                } else {
                    stringResource(Res.string.worktree_review_switch_to_worktree)
                },
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary,
                maxLines = 1,
                modifier = Modifier.handMouseClickable(onClick = onShow),
            )
        }
    }
}

@Composable
internal fun PaneNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
