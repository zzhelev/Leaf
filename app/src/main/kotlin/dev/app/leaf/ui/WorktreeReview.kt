// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.models.BranchComparison
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.icon
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.repositoryopen.WorktreeReviewAction
import dev.app.leaf.repositoryopen.WorktreeReviewState
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.TitleWithTrailingAction
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import kotlinx.coroutines.flow.filterIsInstance
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The commits list takes at most this share of the pane, and the changed files the rest. */
private const val COMMITS_MAX_SHARE = 0.4f

private val SECTION_HEADER_HEIGHT = 34.dp
private val COMMIT_ROW_HEIGHT = 28.dp

/**
 * The review of a worktree's changes (fork-only, Phase 3.3), in place of Files changed: what its branch would bring
 * into the base. A file opens in the diff pane, as a commit's file does.
 */
@Composable
fun WorktreeReview(
    viewModel: RepositoryOpenViewModel,
    reviewState: WorktreeReviewState,
) {
    // Remembered: a new flow at each composition started collecting again
    val diffSelected by remember(viewModel) {
        viewModel.diffSelected.filterIsInstance<DiffSelected.CommitedChanges>()
    }.collectAsState(null)

    WorktreeReviewView(
        state = reviewState,
        diffSelected = diffSelected,
        onAction = { viewModel.onAction(it) },
    )
}

@Composable
internal fun WorktreeReviewView(
    state: WorktreeReviewState,
    diffSelected: DiffSelected.CommitedChanges?,
    onAction: (WorktreeReviewAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .fillMaxSize(),
    ) {
        ReviewHeader(state, onClose = { onAction(WorktreeReviewAction.Close) })

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

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

                if (error != null) {
                    ReviewNote(error.getErrorText(), color = MaterialTheme.colors.error)
                }

                if (comparison != null) {
                    SectionHeader(stringResource(Res.string.worktree_review_commits), comparison.ahead)

                    CommitsList(
                        comparison = comparison,
                        branchName = state.branchName,
                        baseName = state.baseName,
                        modifier = Modifier.heightIn(max = commitsMaxHeight),
                    )

                    SectionHeader(
                        title = stringResource(Res.string.worktree_review_files),
                        count = comparison.changes.size,
                        action = {
                            FilesSortMenuButton(
                                viewState = state.viewState,
                                onViewStateChange = { onAction(WorktreeReviewAction.ViewStateChanged(it)) },
                            )
                        },
                    )

                    when {
                        comparison.mergeBase == null -> ReviewNote(
                            stringResource(Res.string.worktree_review_no_merge_base, state.branchName, state.baseName)
                        )

                        comparison.changes.isEmpty() -> ReviewNote(stringResource(Res.string.worktree_review_no_changes))
                        else -> ChangedFiles(state, diffSelected, onAction, Modifier.weight(1f))
                    }
                }
            }
        }

        UncommittedChangesLine(state, onShow = { onAction(WorktreeReviewAction.ShowUncommittedChanges) })
    }
}

/** "agent1 compared with main", what it shows, the commits that each side has, and the close button. */
@Composable
private fun ReviewHeader(state: WorktreeReviewState, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.worktree_review_title, state.branchName, state.baseName),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = stringResource(Res.string.worktree_review_subtitle, state.branchName, state.baseName),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val comparison = state.comparison

        if (comparison != null) {
            DelayedTooltip(
                stringResource(
                    Res.string.worktree_review_ahead_behind_tooltip,
                    state.branchName,
                    comparison.ahead,
                    state.baseName,
                    comparison.behind,
                )
            ) {
                Text(
                    text = "↑${comparison.ahead} ↓${comparison.behind}",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .handOnHover()
                .size(32.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = stringResource(Res.string.worktree_review_close),
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .height(SECTION_HEADER_HEIGHT)
            .fillMaxWidth()
            .background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun Title() {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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

/** The commits that the branch has and the base doesn't, oldest first, by subject. */
@Composable
private fun CommitsList(
    comparison: BranchComparison,
    branchName: String,
    baseName: String,
    modifier: Modifier = Modifier,
) {
    if (comparison.commits.isEmpty()) {
        ReviewNote(stringResource(Res.string.worktree_review_no_commits, branchName, baseName))
        return
    }

    val listState = rememberInTab("worktreeReviewCommitsScroll", comparison.branch) { LazyListState() }
    val older = comparison.ahead - comparison.commits.size

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        if (older > 0) {
            item(key = "older") {
                ReviewNote(pluralStringResource(Res.plurals.worktree_review_older_commits, older, older))
            }
        }

        items(comparison.commits, key = { it.hash }) { commit ->
            CommitRow(commit)
        }
    }
}

@Composable
private fun CommitRow(commit: Commit) {
    DelayedTooltip(commit.message.trim()) {
        Row(
            modifier = Modifier
                .height(COMMIT_ROW_HEIGHT)
                .fillMaxWidth()
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

/** A commit, drawn as git's commit symbol: a ring on a line. */
@Composable
private fun CommitMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colors.onBackgroundSecondary

    Canvas(modifier = modifier.size(14.dp)) {
        val radius = size.minDimension / 4
        val stroke = 1.5.dp.toPx()

        drawLine(color, Offset(0f, center.y), Offset(center.x - radius, center.y), stroke)
        drawLine(color, Offset(center.x + radius, center.y), Offset(size.width, center.y), stroke)
        drawCircle(color, radius, center, style = Stroke(stroke))
    }
}

@Composable
private fun ChangedFiles(
    state: WorktreeReviewState,
    diffSelected: DiffSelected.CommitedChanges?,
    onAction: (WorktreeReviewAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberInTab("worktreeReviewFilesScroll", state.target) { LazyListState() }

    ChangedFilesList(
        rows = state.rows,
        viewState = state.viewState,
        selectedEntries = diffSelected?.items?.map { it.diffEntry }.orEmpty(),
        listState = listState,
        resetKey = state.target,
        fileIcon = { it.icon },
        fileIconColor = { it.iconColor },
        onFileClick = { onAction(WorktreeReviewAction.SelectEntry(it)) },
        onFolderToggle = { onAction(WorktreeReviewAction.TreeDirectoryToggle(it)) },
        onSplitRatioChange = { onAction(WorktreeReviewAction.ViewStateChanged(state.viewState.copy(splitRatio = it))) },
        onGenerateContextMenu = { emptyList() },
        modifier = modifier,
    )
}

/** The worktree's uncommitted changes, which a merge leaves out, and a link that shows them. */
@Composable
private fun UncommittedChangesLine(state: WorktreeReviewState, onShow: () -> Unit) {
    val count = state.uncommittedChanges ?: return

    if (count == 0) return

    val canShow = state.isTabWorktree || state.canSwitchTo

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
            text = if (state.isTabWorktree) {
                pluralStringResource(Res.plurals.worktree_review_uncommitted_here, count, count)
            } else {
                pluralStringResource(Res.plurals.worktree_review_uncommitted, count, count, state.worktreeName)
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (canShow) {
            Text(
                text = if (state.isTabWorktree) {
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
private fun ReviewNote(text: String, color: Color = MaterialTheme.colors.onBackgroundSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
