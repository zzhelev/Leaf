// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.toolbar

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.ignoreKeyEvents
import dev.app.leaf.theme.frameColors
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.withText
import dev.app.leaf.ui.BelowLeftAlignedPositionProvider
import dev.app.leaf.ui.NewWorktreeRow
import dev.app.leaf.ui.RecentRepositoriesList
import dev.app.leaf.ui.WorktreePickerRow
import dev.app.leaf.ui.WorktreePickerState
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.text
import dev.app.leaf.ui.worktreeTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val SWITCHER_LIST_WIDTH = 420.dp

/** The recent repositories' part of the popup: their search field and list. */
private val RECENT_REPOSITORIES_HEIGHT = 300.dp

private val WORKTREES_MAX_HEIGHT = 240.dp

/** A click on the switcher right after a click outside closed its popup doesn't open it again. */
private const val REOPEN_GUARD_MS = 300

/** What the switcher shows. */
@Immutable
data class SwitcherState(
    val repositoryName: String?,
    /** The linked worktree that the tab shows, or null for the main one. */
    val worktreeName: String?,
    val branchLabel: String,
    /** The repository's worktrees, when it has more than one. */
    val worktrees: WorktreePickerState?,
    val recentRepositories: List<String>,
)

/**
 * What the switcher says the tab has checked out: the worktree list's words when there's one ("rebasing x"), else the
 * current branch, or the commit of a detached HEAD, which is a branch named `HEAD`.
 */
@Composable
fun switcherBranchLabel(currentBranch: Branch?, worktrees: WorktreePickerState?): String {
    val current = worktrees?.rows?.firstOrNull { it.info.worktree.isCurrent }

    return when {
        current != null -> current.head.text()
        currentBranch == null -> ""
        currentBranch.name == "HEAD" -> stringResource(Res.string.side_pane_worktree_detached, currentBranch.hash.take(7))
        else -> currentBranch.simpleName
    }
}

/**
 * The toolbar's switcher (fork-only, docs/fork/window-chrome.md, D2): the repository, the linked worktree and what is
 * checked out, "Leaf / main". Its popup has the repository's worktrees, when there's more than one, then "Open a
 * repository…" and the recent repositories. It took the place of the Open button and the worktree picker, and ⌘O
 * (Ctrl+O) opens it.
 */
@Composable
fun RepositorySwitcher(
    state: SwitcherState,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: SwitcherActions,
    modifier: Modifier = Modifier,
) {
    val frame = MaterialTheme.frameColors
    var dismissedAt by remember { mutableLongStateOf(0L) }
    val repository = state.repositoryName.orEmpty()
    val description = stringResource(Res.string.toolbar_switcher_description, repository, state.branchLabel)
    val shape = RoundedCornerShape(8.dp)

    Box(modifier = modifier) {
        InstantTooltip(text = stringResource(Res.string.toolbar_switcher_tooltip), enabled = !isOpen) {
            Row(
                modifier = Modifier
                    .height(32.dp.withText())
                    .widthIn(max = 360.dp)
                    .ignoreKeyEvents()
                    .clip(shape)
                    .background(frame.control)
                    .border(1.dp, frame.controlEdge, shape)
                    .handMouseClickable {
                        if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                            onOpenChange(!isOpen)
                        }
                    }
                    .padding(start = 10.dp, end = 6.dp)
                    .semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.topic),
                    contentDescription = null,
                    tint = frame.icon,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                )

                Text(
                    text = repository + (state.worktreeName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = frame.text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (state.branchLabel.isNotEmpty()) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.body2,
                        color = frame.secondaryText,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    Icon(
                        painter = painterResource(Res.drawable.branch),
                        contentDescription = null,
                        tint = frame.secondaryText,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(14.dp),
                    )

                    Text(
                        text = state.branchLabel,
                        style = MaterialTheme.typography.body2,
                        color = frame.text,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                Icon(
                    painter = painterResource(Res.drawable.expand_more),
                    contentDescription = null,
                    tint = frame.secondaryText,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp),
                )
            }
        }

        if (isOpen) {
            SwitcherPopup(
                state = state,
                actions = actions,
                onDismissRequest = {
                    onOpenChange(false)
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}

/**
 * The switcher's popup, below it. The recent repositories' search field has the focus, as the Open button's popup
 * had, so that typing filters them; the worktrees take a click.
 */
@Composable
private fun SwitcherPopup(state: SwitcherState, actions: SwitcherActions, onDismissRequest: () -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { BelowLeftAlignedPositionProvider(gap) }
    val searchFocusRequester = remember { FocusRequester() }

    Popup(
        properties = PopupProperties(focusable = true),
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .dropShadow(
                    shape = RoundedCornerShape(8.dp),
                    shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.background)
                .run {
                    if (MaterialTheme.colors.isDark) {
                        border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    } else {
                        this
                    }
                }
                .width(SWITCHER_LIST_WIDTH)
                .padding(vertical = 4.dp),
        ) {
            val worktrees = state.worktrees

            if (worktrees != null) {
                SectionTitle(stringResource(Res.string.side_pane_worktrees_title))

                Column(
                    modifier = Modifier
                        .heightIn(max = WORKTREES_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (row in worktrees.rows) {
                        DelayedTooltip(worktreeTooltip(row.info, worktrees.baseBranch)) {
                            WorktreePickerRow(
                                row = row,
                                onClick = {
                                    actions.onSwitchToWorktree(row.info.worktree)
                                    onDismissRequest()
                                },
                            )
                        }
                    }
                }

                NewWorktreeRow(
                    onClick = {
                        onDismissRequest()
                        actions.onNewWorktree()
                    },
                )

                Divider(modifier = Modifier.padding(vertical = 4.dp))
            }

            OpenRepositoryRow(
                onClick = {
                    onDismissRequest()
                    actions.onOpenRepositoryFromPicker()
                },
            )

            SectionTitle(stringResource(Res.string.toolbar_switcher_recent))

            Box(
                modifier = Modifier
                    .height(RECENT_REPOSITORIES_HEIGHT)
                    .padding(horizontal = 12.dp),
            ) {
                RecentRepositoriesList(
                    recentlyOpenedRepositories = state.recentRepositories,
                    canRepositoriesBeRemoved = false,
                    searchFieldFocusRequester = searchFocusRequester,
                    onRemoveRepositoryFromRecent = {},
                    onOpenKnownRepository = {
                        onDismissRequest()
                        actions.onOpenRepository(it)
                    },
                    onExitClicked = onDismissRequest,
                )
            }
        }

        LaunchedEffect(Unit) {
            searchFocusRequester.requestFocus()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** "Open a repository…", which opens the folder picker. */
@Composable
private fun OpenRepositoryRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp.withText())
            .background(if (isFocused) MaterialTheme.colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .onPreviewKeyEvent { keyEvent ->
                val isActivationKey = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter

                if (isActivationKey) {
                    if (keyEvent.type == KeyEventType.KeyDown) onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.open),
            contentDescription = null,
            tint = MaterialTheme.colors.onBackground,
            modifier = Modifier
                .padding(start = 22.dp, end = 8.dp)
                .size(16.dp),
        )

        Text(
            text = stringResource(Res.string.toolbar_switcher_open),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )
    }
}
