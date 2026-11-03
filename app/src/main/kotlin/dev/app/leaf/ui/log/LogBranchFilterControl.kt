// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.log.isCheckedOut
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.sorting.naturalCompare
import dev.app.leaf.domain.worktrees.nameNextTo
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.repositoryopen.LogBranchFilterState
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.BranchWorktreesState
import dev.app.leaf.ui.WorktreeMark
import dev.app.leaf.ui.components.sort.BelowRightAlignedPositionProvider
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.dialogs.ChoiceGroupHeader
import dev.app.leaf.ui.dialogs.PickRow
import dev.app.leaf.ui.dialogs.branchChoicesKeys
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The log's branch filter (fork-only, upstream issue 207): a control in the log's header, which names what the log
// shows, and its list of branches.

private val LABEL_MAX_WIDTH = 240.dp
private val LIST_WIDTH = 320.dp
private val LIST_MAX_HEIGHT = 440.dp
private val MARK_SIZE = 14.dp

/** A row of the list: one of the two ways to show branches, or a branch's box. */
internal sealed interface LogBranchFilterRow {
    data object AllBranches : LogBranchFilterRow
    data object CurrentBranch : LogBranchFilterRow
    data class BranchBox(val branch: Branch, val matchStart: Int = 0, val matchLength: Int = 0) : LogBranchFilterRow
}

/** The rows of the list for what was typed: the two ways only while nothing is, then local and remote branches. */
internal data class LogBranchFilterRows(
    val modes: List<LogBranchFilterRow>,
    val local: List<LogBranchFilterRow.BranchBox>,
    val remote: List<LogBranchFilterRow.BranchBox>,
) {
    val all: List<LogBranchFilterRow> = modes + local + remote

    /** The first branch's row, which is highlighted once something is typed. */
    val firstBranch: Int get() = if (local.isEmpty() && remote.isEmpty()) -1 else modes.size

    /** Where row [row] is in the list, which has a header before each group of branches. */
    fun itemIndexOf(row: Int): Int = when {
        row < modes.size -> row
        row < modes.size + local.size -> modes.size + 1 + (row - modes.size)
        else -> modes.size + (if (local.isEmpty()) 0 else local.size + 1) + 1 + (row - modes.size - local.size)
    }
}

/**
 * The list's rows for [text]: branches whose name has it, ignoring case, the checked-out one first among the local
 * ones, then by name. A remote branch is matched by its remote's name too (`origin/x`).
 */
internal fun logBranchFilterRows(state: LogBranchFilterState, text: String): LogBranchFilterRows {
    val query = text.trim()

    fun boxes(branches: List<Branch>, name: (Branch) -> String): List<LogBranchFilterRow.BranchBox> = branches
        .sortedWith(
            compareBy<Branch> { !it.isCheckedOut(state.currentBranch) }
                .then { a, b -> naturalCompare(name(a), name(b)) }
        )
        .mapNotNull { branch ->
            if (query.isEmpty()) return@mapNotNull LogBranchFilterRow.BranchBox(branch)

            val start = name(branch).indexOf(query, ignoreCase = true)

            if (start < 0) null else LogBranchFilterRow.BranchBox(branch, start, query.length)
        }

    return LogBranchFilterRows(
        modes = if (query.isEmpty()) {
            listOf(LogBranchFilterRow.AllBranches, LogBranchFilterRow.CurrentBranch)
        } else {
            emptyList()
        },
        local = boxes(state.localBranches) { it.simpleName },
        remote = boxes(state.remoteBranches) { it.simpleNameWithRemote },
    )
}

/** The filter after [row] is picked: one of the two ways, or a branch's box clicked. */
internal fun LogBranchFilterState.picked(row: LogBranchFilterRow): LogBranchFilter = when (row) {
    LogBranchFilterRow.AllBranches -> NO_LOG_BRANCH_FILTER
    LogBranchFilterRow.CurrentBranch -> LogBranchFilter.Current
    is LogBranchFilterRow.BranchBox -> toggled(row.branch)
}

/** What the control says. */
internal sealed interface LogBranchFilterLabel {
    data object AllBranches : LogBranchFilterLabel
    data object CurrentBranch : LogBranchFilterLabel
    data class AllBut(val name: String) : LogBranchFilterLabel
    data class AllButCount(val count: Int) : LogBranchFilterLabel

    /** [names] of the branches shown, the first two of them, and how many more. */
    data class Names(val names: String, val more: Int) : LogBranchFilterLabel
}

/**
 * The control's text: all branches, the current branch, "All but" what's left out, or the names of what's shown. A
 * remote branch whose local branch of the same name is named too is left out of the names, as it is the same line.
 * Names that no branch has any more don't count, and without any, the filter shows the checked-out branch alone.
 */
internal fun logBranchFilterLabel(state: LogBranchFilterState): LogBranchFilterLabel {
    fun names(fullNames: Set<String>): List<String> {
        val chosen = state.branches.filter { it.name in fullNames }
        val locals = chosen.filter { it.isLocal }
        val localNames = locals.mapTo(HashSet()) { it.simpleName }
        val remotes = chosen.filter { it.isRemote && it.simpleName !in localNames }

        return locals.map { it.simpleName }.sortedWith(::naturalCompare) +
            remotes.map { it.simpleNameWithRemote }.sortedWith(::naturalCompare)
    }

    return when (val filter = state.effective) {
        LogBranchFilter.Current -> LogBranchFilterLabel.CurrentBranch
        is LogBranchFilter.All -> {
            val hidden = names(filter.hidden)

            when (hidden.size) {
                0 -> LogBranchFilterLabel.AllBranches
                1 -> LogBranchFilterLabel.AllBut(hidden.single())
                else -> LogBranchFilterLabel.AllButCount(hidden.size)
            }
        }

        is LogBranchFilter.Only -> {
            val shown = names(filter.shown)

            if (shown.isEmpty()) {
                state.currentBranch?.let { LogBranchFilterLabel.Names(it.simpleName, 0) }
                    ?: LogBranchFilterLabel.CurrentBranch
            } else {
                LogBranchFilterLabel.Names(shown.take(2).joinToString(", "), (shown.size - 2).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun LogBranchFilterLabel.text(): String = when (this) {
    LogBranchFilterLabel.AllBranches -> stringResource(Res.string.log_branch_filter_all)
    LogBranchFilterLabel.CurrentBranch -> stringResource(Res.string.log_branch_filter_current)
    is LogBranchFilterLabel.AllBut -> stringResource(Res.string.log_branch_filter_all_but_one, name)
    is LogBranchFilterLabel.AllButCount -> pluralStringResource(Res.plurals.log_branch_filter_all_but, count, count)
    is LogBranchFilterLabel.Names -> if (more == 0) {
        names
    } else {
        stringResource(Res.string.log_branch_filter_and_more, names, more)
    }
}

/**
 * The control in the log's header: what the log shows, and a list to change it. While a filter is on, it's lit as a
 * toggled button, and its × shows every branch again.
 */
@Composable
fun LogBranchFilterButton(
    state: LogBranchFilterState,
    branchWorktrees: BranchWorktreesState,
    onSet: (LogBranchFilter) -> Unit,
) {
    var showList by remember { mutableStateOf(false) }
    // A press on the control while the list is open first dismisses the list as an outside click; this keeps the
    // control's own click from opening it again right away
    var dismissedAt by remember { mutableStateOf(0L) }
    val tooltip = stringResource(Res.string.log_branch_filter_tooltip)
    val label = logBranchFilterLabel(state).text()

    Box {
        Row(
            modifier = Modifier
                .padding(end = 4.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .run { if (state.isOn) background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f)) else this },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DelayedTooltip(tooltip) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .handMouseClickable {
                            if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                                showList = !showList
                            }
                        }
                        .semantics(mergeDescendants = true) { contentDescription = tooltip }
                        .padding(start = 6.dp, end = if (state.isOn) 0.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(Res.drawable.branch),
                        contentDescription = null,
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier.size(MARK_SIZE),
                    )

                    Text(
                        text = label,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).widthIn(max = LABEL_MAX_WIDTH),
                    )

                    Icon(
                        painterResource(Res.drawable.expand_more),
                        contentDescription = null,
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (state.isOn) {
                val showAll = stringResource(Res.string.log_branch_filter_show_all)

                DelayedTooltip(showAll) {
                    Icon(
                        painterResource(Res.drawable.close),
                        contentDescription = showAll,
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier
                            .fillMaxHeight()
                            .handMouseClickable { onSet(NO_LOG_BRANCH_FILTER) }
                            .padding(horizontal = 5.dp)
                            .size(MARK_SIZE),
                    )
                }
            }
        }

        if (showList) {
            val gap = with(LocalDensity.current) { 4.dp.roundToPx() }

            Popup(
                properties = PopupProperties(focusable = true),
                popupPositionProvider = remember(gap) { BelowRightAlignedPositionProvider(gap) },
                onDismissRequest = {
                    showList = false
                    dismissedAt = System.currentTimeMillis()
                },
            ) {
                LogBranchFilterList(
                    state = state,
                    branchWorktrees = branchWorktrees,
                    onSet = onSet,
                    onClose = {
                        showList = false
                        dismissedAt = System.currentTimeMillis()
                    },
                )
            }
        }
    }
}

private const val REOPEN_GUARD_MS = 300

/**
 * The list under the control: a field that keeps the focus and filters the branches, "All branches" and "Current
 * branch", then a box per local and remote branch. Picking a row changes the filter at once and leaves the list open, so
 * that several boxes can be clicked; ↓, ↑ and Enter do the same as the clone dialog's list, and Esc closes it. The
 * checked-out branch's box is checked and can't be cleared.
 */
@Composable
internal fun LogBranchFilterList(
    state: LogBranchFilterState,
    branchWorktrees: BranchWorktreesState,
    onSet: (LogBranchFilter) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var highlighted by remember { mutableStateOf(-1) }
    val rows = logBranchFilterRows(state, text)
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(highlighted, rows) {
        if (highlighted >= 0) listState.keepInView(rows.itemIndexOf(highlighted))
    }

    val pick = { row: LogBranchFilterRow -> onSet(state.picked(row)) }

    Column(
        modifier = Modifier
            .dropShadow(
                shape = RoundedCornerShape(4.dp),
                shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
            )
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colors.background)
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), MaterialTheme.shapes.small)
            .width(LIST_WIDTH)
            .heightIn(max = LIST_MAX_HEIGHT)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                highlighted = if (it.isBlank()) -1 else logBranchFilterRows(state, it).firstBranch
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .focusRequester(focusRequester)
                .branchChoicesKeys(
                    highlighted = highlighted,
                    rowCount = rows.all.size,
                    onHighlight = { highlighted = it },
                    onPick = { index -> rows.all.getOrNull(index)?.let(pick) },
                ),
            decorationBox = { field ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(Res.drawable.search),
                        contentDescription = null,
                        tint = MaterialTheme.colors.onBackgroundSecondary,
                        modifier = Modifier.padding(end = 6.dp).size(MARK_SIZE),
                    )

                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.log_branch_filter_search_hint),
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onBackgroundSecondary,
                                maxLines = 1,
                            )
                        }

                        field()
                    }
                }
            },
        )

        if (rows.all.isEmpty()) {
            Text(
                text = stringResource(Res.string.log_branch_filter_none),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false).padding(bottom = 4.dp),
        ) {
            items(rows.modes, key = { it.toString() }) { row ->
                val index = rows.all.indexOf(row)
                val isAll = row == LogBranchFilterRow.AllBranches
                val isOn = if (isAll) !state.isOn else state.filter == LogBranchFilter.Current

                PickRow(
                    name = stringResource(
                        if (isAll) Res.string.log_branch_filter_all else Res.string.log_branch_filter_current
                    ),
                    matchStart = 0,
                    matchLength = 0,
                    isHighlighted = index == highlighted,
                    onPick = { pick(row) },
                    leading = { RadioMark(isOn) },
                ) {
                    val current = state.currentBranch

                    if (!isAll && current != null) {
                        Text(
                            text = " · " + current.simpleName,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (rows.modes.isNotEmpty()) {
                item(key = "divider") {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            branchBoxes(
                key = "local",
                title = { stringResource(Res.string.log_branch_filter_local) },
                boxes = rows.local,
                firstRow = rows.modes.size,
                highlighted = highlighted,
                state = state,
                branchWorktrees = branchWorktrees,
                onPick = pick,
            )

            branchBoxes(
                key = "remote",
                title = { stringResource(Res.string.log_branch_filter_remote) },
                boxes = rows.remote,
                firstRow = rows.modes.size + rows.local.size,
                highlighted = highlighted,
                state = state,
                branchWorktrees = branchWorktrees,
                onPick = pick,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.branchBoxes(
    key: String,
    title: @Composable () -> String,
    boxes: List<LogBranchFilterRow.BranchBox>,
    firstRow: Int,
    highlighted: Int,
    state: LogBranchFilterState,
    branchWorktrees: BranchWorktreesState,
    onPick: (LogBranchFilterRow) -> Unit,
) {
    if (boxes.isEmpty()) return

    item(key = "$key:header") { ChoiceGroupHeader(title()) }

    items(boxes.size, key = { "$key:" + boxes[it].branch.name }) { index ->
        val box = boxes[index]
        val branch = box.branch
        val isCheckedOut = branch.isCheckedOut(state.currentBranch)
        val other = branchWorktrees.usersOf(branch)?.other

        PickRow(
            name = if (branch.isLocal) branch.simpleName else branch.simpleNameWithRemote,
            matchStart = box.matchStart,
            matchLength = box.matchLength,
            isHighlighted = firstRow + index == highlighted,
            onPick = { onPick(box) },
            fontWeight = if (isCheckedOut) FontWeight.Bold else FontWeight.Normal,
            leading = { CheckMark(isChecked = state.shows(branch), isEnabled = !isCheckedOut) },
        ) {
            if (other != null) {
                Spacer(Modifier.width(8.dp))

                WorktreeMark(other.info.worktree.nameNextTo(branch.name), other.info.origin?.label)
            }
        }
    }
}

/** A branch's box: checked while the log shows the branch. */
@Composable
private fun CheckMark(isChecked: Boolean, isEnabled: Boolean) {
    val accent = MaterialTheme.colors.primary

    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(MARK_SIZE)
            .alpha(if (isEnabled) 1f else 0.5f)
            .clip(RoundedCornerShape(3.dp))
            .run {
                if (isChecked) {
                    background(accent)
                } else {
                    border(1.5.dp, MaterialTheme.colors.onBackgroundSecondary, RoundedCornerShape(3.dp))
                }
            }
            .semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(isChecked)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isChecked) {
            Icon(
                painterResource(Res.drawable.check),
                contentDescription = null,
                tint = MaterialTheme.colors.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** "All branches" or "Current branch": on while it's how the log shows branches. */
@Composable
private fun RadioMark(isOn: Boolean) {
    val accent = MaterialTheme.colors.primaryVariant

    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(MARK_SIZE)
            .border(1.5.dp, if (isOn) accent else MaterialTheme.colors.onBackgroundSecondary, CircleShape)
            .semantics {
                role = Role.RadioButton
                toggleableState = ToggleableState(isOn)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isOn) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
        }
    }
}

/** Scrolls the least that shows item [index] in full. */
private suspend fun LazyListState.keepInView(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: return
    val last = visible.lastOrNull()?.index ?: return

    if (index <= first || index >= last) {
        scrollToItem((index - if (index >= last) (last - first - 1).coerceAtLeast(0) else 0).coerceAtLeast(0))
    }
}
