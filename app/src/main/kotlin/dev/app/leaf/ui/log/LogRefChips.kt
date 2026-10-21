// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.cloud
import dev.app.leaf.app.generated.resources.log_ref_chip_shared_remotes
import dev.app.leaf.app.generated.resources.log_ref_chips_branches
import dev.app.leaf.app.generated.resources.log_ref_chips_hint
import dev.app.leaf.app.generated.resources.log_ref_chips_remote_branches
import dev.app.leaf.app.generated.resources.log_ref_chips_tags
import dev.app.leaf.domain.log.LogRef
import dev.app.leaf.domain.log.fitChips
import dev.app.leaf.domain.log.logRefs
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.BelowLeftAlignedPositionProvider
import dev.app.leaf.ui.LocalBranchWorktrees
import dev.app.leaf.ui.LocalOnChooseWorktreesBase
import dev.app.leaf.ui.LocalOnSwitchToWorktree
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.CompareWithCurrent
import dev.app.leaf.ui.context_menu.LocalCompareWithCurrent
import dev.app.leaf.ui.context_menu.sharedChipRemoteItems
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Share of the Message column that a row's chips may take, as long as the message keeps [MIN_MESSAGE_WIDTH]. Only the
 * first chip goes past it (see [fitChips]).
 */
private const val REF_CHIPS_SHARE = 0.8f

/** Widest that a chip gets in a row. A longer name ends in "…", and its tooltip has the whole name. */
private val MAX_REF_CHIP_WIDTH = 250.dp

/**
 * Narrowest that the first chip gets when it's cut down to fit. With every mark (86 dp at most), the name keeps about
 * three letters. Below this, +N stands for every ref.
 */
private val MIN_REF_CHIP_WIDTH = 120.dp

/** The message keeps at least this much of the Message column, whatever the chips' share. */
private val MIN_MESSAGE_WIDTH = 64.dp

/** Space between the graph and the first chip, or the message on a row without chips. */
val REF_CHIPS_START_PADDING = 16.dp

/** A click on +N right after a click outside closed the list doesn't open it again. */
private const val REOPEN_GUARD_MS = 300

/** What a log row's chips do with its branches and tags (fork-only). */
class LogRefActions(
    val onCheckoutBranch: (Branch) -> Unit,
    val onMergeBranch: (Branch) -> Unit,
    val onDeleteBranch: (Branch) -> Unit,
    val onDeleteRemoteBranch: (Branch) -> Unit,
    val onRebaseBranch: (Branch) -> Unit,
    val onCheckoutTag: (Tag) -> Unit,
    val onDeleteTag: (Tag) -> Unit,
    val onPushRemoteBranch: (Branch) -> Unit,
    val onPullRemoteBranch: (Branch) -> Unit,
    val onChangeDefaultUpstreamBranch: (Branch) -> Unit,
    val onRenameBranch: (Branch) -> Unit,
    val onCopyBranchNameToClipboard: (Branch) -> Unit,
) {
    /** The same actions, each of which first runs [dismiss], so that a dialog it opens isn't behind a popup. */
    fun dismissingFirst(dismiss: () -> Unit): LogRefActions {
        fun <T> first(action: (T) -> Unit): (T) -> Unit = { dismiss(); action(it) }

        return LogRefActions(
            onCheckoutBranch = first(onCheckoutBranch),
            onMergeBranch = first(onMergeBranch),
            onDeleteBranch = first(onDeleteBranch),
            onDeleteRemoteBranch = first(onDeleteRemoteBranch),
            onRebaseBranch = first(onRebaseBranch),
            onCheckoutTag = first(onCheckoutTag),
            onDeleteTag = first(onDeleteTag),
            onPushRemoteBranch = first(onPushRemoteBranch),
            onPullRemoteBranch = first(onPullRemoteBranch),
            onChangeDefaultUpstreamBranch = first(onChangeDefaultUpstreamBranch),
            onRenameBranch = first(onRenameBranch),
            onCopyBranchNameToClipboard = first(onCopyBranchNameToClipboard),
        )
    }
}

private enum class OverflowSlot { Chip, Probe, CutFirstChip }

/**
 * A commit's branches and tags at the start of its log row (fork-only), in the order of [logRefs], with a remote branch
 * on the chip of the local branch of its name. They take at most [REF_CHIPS_SHARE] of the Message column, and leave the
 * message [MIN_MESSAGE_WIDTH], except that the first one shows even when it alone doesn't fit that way (see
 * [fitChips]). What doesn't fit goes into a +N chip, which opens a list of all the commit's refs, each as a chip of its
 * own.
 *
 * Before, the chips took all the width they asked for: the ones past the row's end were cut off unseen, and the
 * message, Author and Date had what was left.
 */
@Composable
fun LogRefChips(
    branches: List<Branch>,
    tags: List<Tag>,
    currentBranch: Branch?,
    color: Color,
    actions: LogRefActions,
    /** The room before the first chip; a comparison's badge takes it (fork-only, `docs/fork/compare.md`). */
    startPadding: Dp = REF_CHIPS_START_PADDING,
) {
    if (branches.isEmpty() && tags.isEmpty()) {
        Spacer(Modifier.width(startPadding))
        return
    }

    val branchWorktrees = LocalBranchWorktrees.current
    val refs = remember(branches, tags, currentBranch, branchWorktrees) {
        logRefs(
            branches = branches,
            tags = tags,
            currentBranch = currentBranch,
            isUsedByOtherWorktree = { branchWorktrees.usersOf(it)?.other != null },
            mergeRemotes = true,
        )
    }

    var showAll by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableLongStateOf(0L) }

    SubcomposeLayout { constraints ->
        val start = startPadding.roundToPx()
        val column = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE / 2
        val messageRoom = MIN_MESSAGE_WIDTH.roundToPx()
        val share = minOf((column * REF_CHIPS_SHARE).toInt(), column - messageRoom) - start
        val chipConstraints = Constraints(maxWidth = MAX_REF_CHIP_WIDTH.roundToPx())

        var chips: List<List<Placeable>> = refs.map { ref ->
            subcompose(ref.key) { LogRefChip(ref, currentBranch, color, actions) }.map { it.measure(chipConstraints) }
        }
        var shown = refs.size
        var overflow = emptyList<Placeable>()

        val widths = chips.map { chip -> chip.sumOf { it.width } }

        if (widths.sum() > share) {
            // The widest that +N gets, for every ref
            val overflowWidth = subcompose(OverflowSlot.Probe) { OverflowChip(refs.size, color) }
                .sumOf { it.measure(Constraints()).width }

            val fit = fitChips(
                widths = widths,
                share = share,
                firstChipRoom = column - start - messageRoom,
                overflowWidth = overflowWidth,
                minChipWidth = MIN_REF_CHIP_WIDTH.roundToPx(),
            )

            shown = fit.shown

            val cutFirstTo = fit.cutFirstTo

            if (cutFirstTo != null) {
                chips = listOf(
                    subcompose(OverflowSlot.CutFirstChip) { LogRefChip(refs.first(), currentBranch, color, actions) }
                        .map { it.measure(Constraints(maxWidth = cutFirstTo)) }
                )
            }
        }

        if (shown < refs.size) {
            val hidden = refs.drop(shown)

            overflow = subcompose(OverflowSlot.Chip) {
                Box {
                    DisposableEffect(Unit) {
                        onDispose { showAll = false }
                    }

                    DelayedTooltip(if (showAll) null else hidden.joinToString("\n") { it.label }) {
                        OverflowChip(
                            count = hidden.size,
                            color = color,
                            modifier = Modifier.onClickLettingThrough {
                                if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                                    showAll = !showAll
                                }
                            },
                        )
                    }

                    if (showAll) {
                        AllRefsPopup(
                            branches = branches,
                            tags = tags,
                            currentBranch = currentBranch,
                            color = color,
                            actions = actions,
                            onDismissRequest = {
                                showAll = false
                                dismissedAt = System.currentTimeMillis()
                            },
                        )
                    }
                }
            }.map { it.measure(Constraints()) }
        }

        val placeables = chips.take(shown).flatten() + overflow
        val height = placeables.maxOfOrNull { it.height } ?: 0

        layout(start + placeables.sumOf { it.width }, height) {
            var x = start

            for (placeable in placeables) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/** The chip of a branch or a tag, which double-clicks and right-clicks as before. */
@Composable
private fun LogRefChip(ref: LogRef, currentBranch: Branch?, color: Color, actions: LogRefActions) {
    when (ref) {
        is LogRef.LocalBranch -> BranchRefChip(ref.branch, ref.remotes, currentBranch, color, actions)
        is LogRef.RemoteBranch -> BranchRefChip(ref.branch, emptyList(), currentBranch, color, actions)
        is LogRef.TagRef -> TagChip(
            tag = ref.tag,
            color = color,
            onCheckoutTag = { actions.onCheckoutTag(ref.tag) },
            onDeleteTag = { actions.onDeleteTag(ref.tag) },
        )
    }
}

@Composable
private fun BranchRefChip(
    branch: Branch,
    sharedRemotes: List<Branch>,
    currentBranch: Branch?,
    color: Color,
    actions: LogRefActions,
) {
    val branchWorktrees = LocalBranchWorktrees.current
    val onChooseWorktreesBase = LocalOnChooseWorktreesBase.current
    val compareWithCurrent = LocalCompareWithCurrent.current

    BranchChip(
        branch = branch,
        color = color,
        currentBranch = currentBranch,
        isCurrentBranch = branch.isSameBranch(currentBranch),
        onCheckoutBranch = actions.onCheckoutBranch,
        onMergeBranch = actions.onMergeBranch,
        onDeleteBranch = actions.onDeleteBranch,
        onDeleteRemoteBranch = actions.onDeleteRemoteBranch,
        onRebaseBranch = actions.onRebaseBranch,
        onPullRemoteBranch = actions.onPullRemoteBranch,
        onPushRemoteBranch = actions.onPushRemoteBranch,
        onChangeDefaultUpstreamBranch = actions.onChangeDefaultUpstreamBranch,
        onRenameBranch = actions.onRenameBranch,
        onCopyBranchNameToClipboard = actions.onCopyBranchNameToClipboard,
        sharedRemotes = sharedRemotes,
        sharedRemotesMenuItems = {
            sharedChipRemoteItems(
                remotes = sharedRemotes,
                currentBranch = currentBranch,
                onPushToRemoteBranch = actions.onPushRemoteBranch,
                onPullFromRemoteBranch = actions.onPullRemoteBranch,
                onDeleteRemoteBranch = actions.onDeleteRemoteBranch,
                onCopyBranchNameToClipboard = actions.onCopyBranchNameToClipboard,
                worktreesBase = branchWorktrees.baseChoice,
                onChooseWorktreesBase = onChooseWorktreesBase,
                onCompareWithCurrent = compareWithCurrent?.onBranch,
            )
        },
    )
}

/** The mark on a local branch's chip of the remote branches that share it, which its tooltip names. */
@Composable
fun SharedRemotesChipMark(remotes: List<Branch>, modifier: Modifier = Modifier) {
    val names = remotes.joinToString(", ") { it.logName }

    DelayedTooltip(stringResource(Res.string.log_ref_chip_shared_remotes, names), modifier = modifier) {
        Icon(
            painter = painterResource(Res.drawable.cloud),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colors.onBackgroundSecondary,
        )
    }
}

/** The +N chip, as tall as the other chips, that stands for the refs that don't fit. */
@Composable
private fun OverflowChip(count: Int, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clip(shape)
            .border(width = 2.dp, color = color, shape = shape)
            .handOnHover()
            .height(14.dp + MaterialTheme.linesHeight.refChipIconPadding * 2)
            .padding(horizontal = 8.dp)
            .wrapContentHeight(),
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Runs [onClick] on a click without taking it, so that the click also reaches the commit's row and selects the commit.
 */
@Composable
private fun Modifier.onClickLettingThrough(onClick: () -> Unit): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)

    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            waitForUpOrCancellation() ?: return@awaitEachGesture
            currentOnClick()
        }
    }
}

/**
 * The list of all a commit's refs, below its +N chip or above it when there's no room: local branches, remote branches,
 * then tags, each a chip of its own that double-clicks and right-clicks as in the row. An action closes the list
 * first, as does Esc or a click outside.
 */
@Composable
private fun AllRefsPopup(
    branches: List<Branch>,
    tags: List<Tag>,
    currentBranch: Branch?,
    color: Color,
    actions: LogRefActions,
    onDismissRequest: () -> Unit,
) {
    val branchWorktrees = LocalBranchWorktrees.current
    val groups = remember(branches, tags, currentBranch, branchWorktrees) {
        val refs = logRefs(
            branches = branches,
            tags = tags,
            currentBranch = currentBranch,
            isUsedByOtherWorktree = { branchWorktrees.usersOf(it)?.other != null },
            mergeRemotes = false,
        )

        listOf<Pair<StringResource, List<LogRef>>>(
            Res.string.log_ref_chips_branches to refs.filterIsInstance<LogRef.LocalBranch>(),
            Res.string.log_ref_chips_remote_branches to refs.filterIsInstance<LogRef.RemoteBranch>(),
            Res.string.log_ref_chips_tags to refs.filterIsInstance<LogRef.TagRef>(),
        ).filter { (_, groupRefs) -> groupRefs.isNotEmpty() }
    }

    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { BelowLeftAlignedPositionProvider(gap) }
    val popupActions = actions.dismissingFirst(onDismissRequest)
    val onSwitchToWorktree = LocalOnSwitchToWorktree.current
    val onChooseWorktreesBase = LocalOnChooseWorktreesBase.current
    val compareWithCurrent = LocalCompareWithCurrent.current

    Popup(
        properties = PopupProperties(focusable = true),
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
    ) {
        val focusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        CompositionLocalProvider(
            LocalOnSwitchToWorktree provides { worktree ->
                onDismissRequest()
                onSwitchToWorktree(worktree)
            },
            LocalOnChooseWorktreesBase provides { base ->
                onDismissRequest()
                onChooseWorktreesBase(base)
            },
            LocalCompareWithCurrent provides compareWithCurrent?.let { compare ->
                CompareWithCurrent(
                    currentBranch = compare.currentBranch,
                    onBranch = { onDismissRequest(); compare.onBranch(it) },
                    onTag = { onDismissRequest(); compare.onTag(it) },
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .dropShadow(
                        shape = RoundedCornerShape(4.dp),
                        shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
                    )
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colors.background)
                    .run {
                        if (MaterialTheme.colors.isDark) {
                            border(2.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                        } else {
                            this
                        }
                    }
                    .widthIn(min = 220.dp, max = 420.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.matchesBinding(KeybindingOption.EXIT)) {
                            onDismissRequest()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                for ((title, groupRefs) in groups) {
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    )

                    for (ref in groupRefs) {
                        Box(modifier = Modifier.padding(bottom = 4.dp)) {
                            LogRefChip(ref, currentBranch, color, popupActions)
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.log_ref_chips_hint),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                )
            }
        }
    }
}

/** A ref's name as its chip shows it, with the remote branches that share the chip. */
private val LogRef.label: String
    get() = when (this) {
        is LogRef.LocalBranch -> (listOf(branch) + remotes).joinToString(", ") { it.logName }
        is LogRef.RemoteBranch -> branch.logName
        is LogRef.TagRef -> tag.simpleName
    }
