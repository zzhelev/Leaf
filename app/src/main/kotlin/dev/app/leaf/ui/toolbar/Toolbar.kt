// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.worktrees.TabNames
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.ignoreKeyEvents
import dev.app.leaf.keybindings.Keybinding
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.frameColors
import dev.app.leaf.theme.withText
import dev.app.leaf.ui.KeybindingHint
import dev.app.leaf.ui.WorktreePickerState
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.context_menu.DropDownMenu
import dev.app.leaf.ui.context_menu.pullContextMenuItems
import dev.app.leaf.ui.context_menu.pushContextMenuItems
import dev.app.leaf.ui.context_menu.stashContextMenuItems
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The toolbar's height (mockup A of the "Leaf window chrome review" board), at the default text size. */
val TOOLBAR_HEIGHT = 48.dp

/** The height of the toolbar's buttons, groups and switcher, at the default text size. */
private val CONTROL_HEIGHT = 32.dp

private val CONTROL_SHAPE = RoundedCornerShape(8.dp)

private const val DISABLED_ALPHA = 0.45f

/**
 * Which labels the toolbar has room for at [width] (fork-only, docs/fork/window-chrome.md): Branch, Stash and Pop lose
 * theirs first, then Pull and Push. Their icons and tooltips stay. Actions keeps its label. The rule holds at every text
 * size: at 20, the row still has about 90 dp to spare where the labels come back.
 */
@Immutable
data class ToolbarLabels(
    val showsBranchAndStashLabels: Boolean,
    val showsPullAndPushLabels: Boolean,
)

fun toolbarLabelsFor(width: Dp): ToolbarLabels = ToolbarLabels(
    showsBranchAndStashLabels = width >= 1150.dp,
    showsPullAndPushLabels = width >= 1000.dp,
)

/** What the toolbar's buttons do. */
class ToolbarActions(
    val onPull: (PullType) -> Unit,
    val onFetchAll: () -> Unit,
    val onPush: (pushTags: Boolean) -> Unit,
    val onForcePush: () -> Unit,
    val onCreateBranch: () -> Unit,
    val onStash: () -> Unit,
    val onStashWithMessage: () -> Unit,
    val onPopStash: () -> Unit,
    val onQuickActions: () -> Unit,
    val onTerminal: () -> Unit,
    val onSettings: () -> Unit,
    val switcher: SwitcherActions,
)

/**
 * The toolbar of a repository's tab (fork-only, docs/fork/window-chrome.md, step 3), on the frame's colours: where
 * you are on the left (the switcher), what you do in the middle (Pull and Push, Branch, Stash and Pop), and
 * Actions, Terminal and Settings on the right. It replaced upstream's toolbar, which `Menu.kt` held.
 */
@Composable
fun Toolbar(
    viewModel: RepositoryOpenViewModel,
    repositoryNames: TabNames?,
    showSwitcher: Boolean,
    onShowSwitcherChange: (Boolean) -> Unit,
    actions: ToolbarActions,
    modifier: Modifier = Modifier,
) {
    val isPullWithRebaseDefault by viewModel.isPullWithRebaseDefault.collectAsState(false)
    val lastLoadedTabs by viewModel.lastLoadedTabs.collectAsState()
    val hasUncommittedChanges by viewModel.hasUncommittedChanges.collectAsState()
    val stashesState by viewModel.stashesState.collectAsState()
    val remotes by viewModel.remoteState.collectAsState()
    val worktreePickerState by viewModel.worktreePickerState.collectAsState()
    val branchesState by viewModel.branchesState.collectAsState()

    ToolbarRow(
        state = ToolbarState(
            switcher = SwitcherState(
                repositoryName = repositoryNames?.repository,
                worktreeName = repositoryNames?.worktree,
                branchLabel = switcherBranchLabel(branchesState.currentBranch, worktreePickerState),
                worktrees = worktreePickerState,
                recentRepositories = lastLoadedTabs,
            ),
            hasRemotes = remotes.remotes.isNotEmpty(),
            isPullWithRebaseDefault = isPullWithRebaseDefault,
            canStash = hasUncommittedChanges,
            canPopStash = stashesState.stashes.isNotEmpty(),
        ),
        showSwitcher = showSwitcher,
        onShowSwitcherChange = onShowSwitcherChange,
        actions = actions,
        modifier = modifier,
    )
}

/** What the toolbar shows. */
@Immutable
data class ToolbarState(
    val switcher: SwitcherState,
    val hasRemotes: Boolean,
    val isPullWithRebaseDefault: Boolean,
    val canStash: Boolean,
    val canPopStash: Boolean,
)

@Composable
fun ToolbarRow(
    state: ToolbarState,
    showSwitcher: Boolean,
    onShowSwitcherChange: (Boolean) -> Unit,
    actions: ToolbarActions,
    modifier: Modifier = Modifier,
) {
    val frame = MaterialTheme.frameColors

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT.withText())
            .background(frame.background)
            .bottomEdge(frame.separator),
    ) {
        val labels = toolbarLabelsFor(maxWidth)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepositorySwitcher(
                    state = state.switcher,
                    isOpen = showSwitcher,
                    onOpenChange = onShowSwitcherChange,
                    actions = actions.switcher,
                    modifier = Modifier.weight(1f, fill = false),
                )

                // The frame's own edge: the separators are the content's, which a dark frame on the light side isn't
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = 1.dp, height = 20.dp)
                        .background(frame.controlEdge)
                )

                RemoteGroup(state, labels.showsPullAndPushLabels, actions)

                ToolbarButton(
                    label = stringResource(Res.string.menu_branch).takeIf { labels.showsBranchAndStashLabels },
                    icon = painterResource(Res.drawable.branch),
                    tooltip = stringResource(Res.string.menu_branch_tooltip),
                    keybinding = KeybindingOption.BRANCH_CREATE.keyBinding,
                    onClick = actions.onCreateBranch,
                )

                StashGroup(state, labels.showsBranchAndStashLabels, actions)
            }

            Spacer(Modifier.width(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionsButton(onClick = actions.onQuickActions)

                Spacer(Modifier.width(6.dp))

                ToolbarIconButton(
                    icon = painterResource(Res.drawable.terminal),
                    label = stringResource(Res.string.menu_terminal),
                    tooltip = stringResource(Res.string.menu_terminal_tooltip),
                    keybinding = null,
                    onClick = actions.onTerminal,
                )

                ToolbarIconButton(
                    icon = painterResource(Res.drawable.settings),
                    label = stringResource(Res.string.menu_settings),
                    tooltip = stringResource(Res.string.menu_settings_tooltip),
                    keybinding = KeybindingOption.SETTINGS.keyBinding,
                    onClick = actions.onSettings,
                )
            }
        }
    }
}

@Composable
private fun RemoteGroup(state: ToolbarState, showsLabels: Boolean, actions: ToolbarActions) {
    val enabled = state.hasRemotes
    val pullTooltip = when {
        !enabled -> stringResource(Res.string.menu_pull_tooltip_disabled)
        state.isPullWithRebaseDefault -> stringResource(Res.string.menu_pull_rebase)
        else -> stringResource(Res.string.menu_pull_default)
    }
    val pushTooltip = if (enabled) {
        stringResource(Res.string.menu_push_tooltip)
    } else {
        stringResource(Res.string.menu_push_tooltip_disabled)
    }

    ToolbarGroup {
        GroupSegment(
            label = stringResource(Res.string.menu_pull),
            showsLabel = showsLabels,
            icon = painterResource(Res.drawable.download),
            tooltip = pullTooltip,
            keybinding = KeybindingOption.PULL.keyBinding,
            enabled = enabled,
            onClick = { actions.onPull(PullType.DEFAULT) },
        )
        GroupMenu(
            label = stringResource(Res.string.toolbar_pull_options),
            enabled = enabled,
            items = {
                pullContextMenuItems(
                    isPullWithRebaseDefault = state.isPullWithRebaseDefault,
                    // The other way than the default
                    onPullWith = { actions.onPull(if (state.isPullWithRebaseDefault) PullType.MERGE else PullType.REBASE) },
                    onFetchAll = actions.onFetchAll,
                )
            },
        )
        GroupDivider()
        GroupSegment(
            label = stringResource(Res.string.menu_push),
            showsLabel = showsLabels,
            icon = painterResource(Res.drawable.upload),
            tooltip = pushTooltip,
            keybinding = KeybindingOption.PUSH.keyBinding,
            enabled = enabled,
            onClick = { actions.onPush(false) },
        )
        GroupMenu(
            label = stringResource(Res.string.toolbar_push_options),
            enabled = enabled,
            items = {
                pushContextMenuItems(
                    onPushWithTags = { actions.onPush(true) },
                    onForcePush = actions.onForcePush,
                )
            },
        )
    }
}

@Composable
private fun StashGroup(state: ToolbarState, showsLabels: Boolean, actions: ToolbarActions) {
    ToolbarGroup {
        GroupSegment(
            label = stringResource(Res.string.menu_stash),
            showsLabel = showsLabels,
            icon = painterResource(Res.drawable.stash),
            tooltip = if (state.canStash) {
                stringResource(Res.string.menu_stash_tooltip)
            } else {
                stringResource(Res.string.menu_stash_tooltip_disabled)
            },
            keybinding = KeybindingOption.STASH.keyBinding,
            enabled = state.canStash,
            onClick = actions.onStash,
        )
        GroupMenu(
            label = stringResource(Res.string.toolbar_stash_options),
            enabled = state.canStash,
            items = { stashContextMenuItems(onStashWithMessage = actions.onStashWithMessage) },
        )
        GroupDivider()
        GroupSegment(
            label = stringResource(Res.string.toolbar_pop),
            showsLabel = showsLabels,
            icon = painterResource(Res.drawable.apply_stash),
            tooltip = if (state.canPopStash) {
                stringResource(Res.string.menu_pop_stash_tooltip)
            } else {
                stringResource(Res.string.menu_pop_stash_tooltip_disabled)
            },
            keybinding = KeybindingOption.STASH_POP.keyBinding,
            enabled = state.canPopStash,
            onClick = actions.onPopStash,
        )
    }
}

/** Buttons that belong together, in one outline with lines between them. */
@Composable
private fun ToolbarGroup(content: @Composable RowScope.() -> Unit) {
    val frame = MaterialTheme.frameColors

    Row(
        modifier = Modifier
            .height(CONTROL_HEIGHT.withText())
            .clip(CONTROL_SHAPE)
            .background(frame.control)
            .border(1.dp, frame.controlEdge, CONTROL_SHAPE),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.frameColors.controlEdge)
    )
}

/** A button in a group: its icon, and its label when [showsLabel]. */
@Composable
private fun GroupSegment(
    label: String,
    showsLabel: Boolean,
    icon: Painter,
    tooltip: String,
    keybinding: Keybinding?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ToolbarTooltip(tooltip, keybinding.takeIf { enabled }) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .ignoreKeyEvents()
                .handMouseClickable(enabled) { onClick() }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(start = 10.dp, end = if (showsLabel) 8.dp else 10.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlLabel(icon, label.takeIf { showsLabel })
        }
    }
}

/** A group's ⌄, which opens [items]. */
@Composable
private fun GroupMenu(label: String, enabled: Boolean, items: () -> List<dev.app.leaf.ui.context_menu.ContextMenuElement>) {
    DropDownMenu(enabled = enabled, items = items) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .ignoreKeyEvents()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(end = 4.dp)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.expand_more),
                contentDescription = null,
                tint = MaterialTheme.frameColors.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A button of its own, outlined as a group is, with its label when there's one. */
@Composable
private fun ToolbarButton(
    label: String?,
    icon: Painter,
    tooltip: String,
    keybinding: Keybinding?,
    onClick: () -> Unit,
) {
    val frame = MaterialTheme.frameColors

    ToolbarTooltip(tooltip, keybinding) {
        Row(
            modifier = Modifier
                .height(CONTROL_HEIGHT.withText())
                .ignoreKeyEvents()
                .clip(CONTROL_SHAPE)
                .background(frame.control)
                .border(1.dp, frame.controlEdge, CONTROL_SHAPE)
                .handMouseClickable { onClick() }
                .padding(start = 10.dp, end = if (label != null) 12.dp else 10.dp)
                .semantics { contentDescription = tooltip },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlLabel(icon, label)
        }
    }
}

@Composable
private fun ControlLabel(icon: Painter, label: String?) {
    val frame = MaterialTheme.frameColors

    Icon(
        painter = icon,
        contentDescription = null,
        tint = frame.text,
        modifier = Modifier.size(16.dp),
    )

    if (label != null) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = frame.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** A button that is only an icon, without an outline until it's hovered. */
@Composable
private fun ToolbarIconButton(
    icon: Painter,
    label: String,
    tooltip: String,
    keybinding: Keybinding?,
    onClick: () -> Unit,
) {
    val frame = MaterialTheme.frameColors
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()

    ToolbarTooltip(tooltip, keybinding) {
        Box(
            modifier = Modifier
                .size(CONTROL_HEIGHT)
                .ignoreKeyEvents()
                .clip(CONTROL_SHAPE)
                .background(if (isHovered) frame.control else Color.Transparent)
                .hoverable(hover)
                .handMouseClickable { onClick() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = frame.secondaryText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Actions: a button as Branch is, with its shortcut after its label, which opens the Actions list. It was a button that
 * looked like a search field, which read as a field that could be typed in.
 */
@Composable
private fun ActionsButton(onClick: () -> Unit) {
    val frame = MaterialTheme.frameColors
    val keybinding = KeybindingOption.QUICK_ACTIONS.keyBinding
    val label = stringResource(Res.string.menu_actions)

    ToolbarTooltip(stringResource(Res.string.toolbar_actions_tooltip), null) {
        Row(
            modifier = Modifier
                .height(CONTROL_HEIGHT.withText())
                .ignoreKeyEvents()
                .clip(CONTROL_SHAPE)
                .background(frame.control)
                .border(1.dp, frame.controlEdge, CONTROL_SHAPE)
                .handMouseClickable { onClick() }
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlLabel(painterResource(Res.drawable.bolt), label)

            if (keybinding != null) {
                Box(Modifier.padding(start = 10.dp)) {
                    KeybindingHint(keybinding, color = frame.secondaryText)
                }
            }
        }
    }
}

/**
 * A toolbar control with its tooltip. The tooltip's popup takes a place, of no size, in the layout it's in, so it goes
 * in a box of its own: in the toolbar's rows, which space their children, it got a gap that moved the controls after
 * it while the tooltip showed.
 */
@Composable
private fun ToolbarTooltip(text: String, keybinding: Keybinding?, content: @Composable () -> Unit) {
    Box {
        InstantTooltip(
            text = text,
            trailingContent = keybinding?.let { { KeybindingHint(it) } },
            content = content,
        )
    }
}

/** What the switcher's popup does: the worktree list's switching, and opening another repository. */
class SwitcherActions(
    val onSwitchToWorktree: (Worktree) -> Unit,
    val onNewWorktree: () -> Unit,
    val onOpenRepository: (String) -> Unit,
    val onOpenRepositoryFromPicker: () -> Unit,
)
