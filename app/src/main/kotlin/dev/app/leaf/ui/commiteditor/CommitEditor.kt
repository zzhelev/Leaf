// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.commiteditor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanRow
import dev.app.leaf.domain.rewrite.PlanRowMarks
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.repositoryopen.CommitEditorAction
import dev.app.leaf.repositoryopen.CommitEditorState
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.drag_sorting.VerticalDraggableItem
import dev.app.leaf.ui.drag_sorting.rememberVerticalDragDropState
import dev.app.leaf.ui.drag_sorting.verticalDragContainer
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A row of the editor's list. */
private val ROW_HEIGHT = 34.dp

/** The action's button at the start of a row, as wide as its longest name. */
private val PILL_WIDTH = 74.dp

/**
 * The commit editor (fork-only, step B5 of `docs/fork/commit-editor.md`), in the centre pane: the plan's rows newest
 * first, P R S F E D for an action, ⌥↑↓ or a drag to move a row, and the footer that says what Start does, where the
 * plan stops and what is in the way. The selected row's message is in the panel ([CommitEditorPanel]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommitEditor(
    state: CommitEditorState,
    onAction: (CommitEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listFocus = remember { FocusRequester() }
    val plan = state.plan
    val shownRows = plan.rows.asReversed()
    val firstKept = plan.rows.firstOrNull { it.action != PlanAction.DROP }?.commit?.hash
    val currentOnAction by rememberUpdatedState(onAction)

    LaunchedEffect(Unit) {
        runCatching { listFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onPreviewKeyEvent { event -> startKey(event, onAction) },
    ) {
        EditorHeader(state, onAction)

        val fixups = plan.misplacedFixups.size

        if (fixups > 0) {
            FixupsStrip(count = fixups, onPlace = { onAction(CommitEditorAction.PlaceFixups) })
        }

        Divider()

        val listState = rememberLazyListState()
        val dragState = rememberVerticalDragDropState(listState) { from, to ->
            currentOnAction(CommitEditorAction.MoveShown(from, to))
        }

        ScrollableLazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(listFocus)
                .focusable()
                .onPreviewKeyEvent { event -> listKey(event, state, onAction) }
                .verticalDragContainer(dragState, onDraggedItem = { index ->
                    shownRows.getOrNull(index)?.let { onAction(CommitEditorAction.Select(it.commit.hash)) }
                }),
            state = listState,
        ) {
            itemsIndexed(shownRows, key = { _, row -> row.commit.hash }) { index, row ->
                VerticalDraggableItem(dragState, index) { isDragging ->
                    PlanRowView(
                        row = row,
                        marks = state.marks[row.commit.hash] ?: PlanRowMarks(),
                        isSelected = row.commit.hash == state.selected,
                        isDragging = isDragging,
                        isInGroup = isInGroup(state, row),
                        isFirstKept = row.commit.hash == firstKept,
                        base = plan.onto?.base ?: plan.line.base,
                        upstream = plan.line.upstream,
                        onClick = {
                            onAction(CommitEditorAction.Select(row.commit.hash))
                            runCatching { listFocus.requestFocus() }
                        },
                        onAction = { action -> onAction(CommitEditorAction.SetAction(row.commit.hash, action)) },
                    )
                }
            }
        }

        Divider()
        EditorFooter(state, onAction)
    }
}

/** ⌘↵ (Ctrl+Enter elsewhere) starts the plan from anywhere in the editor. */
private fun startKey(event: KeyEvent, onAction: (CommitEditorAction) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return false

    val command = if (currentOs.isMac()) event.isMetaPressed else event.isCtrlPressed

    if (!command) return false

    onAction(CommitEditorAction.Start)

    return true
}

/** The list's keys: ↑↓ select, ⌥↑↓ move, P R S F E D set the selected row's action. */
internal fun listKey(event: KeyEvent, state: CommitEditorState, onAction: (CommitEditorAction) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isMetaPressed || event.isCtrlPressed || event.isShiftPressed) return false

    val action = when (event.key) {
        Key.DirectionUp -> if (event.isAltPressed) CommitEditorAction.MoveSelected(newer = true) else CommitEditorAction.SelectNext(newer = true)
        Key.DirectionDown -> if (event.isAltPressed) CommitEditorAction.MoveSelected(newer = false) else CommitEditorAction.SelectNext(newer = false)
        else -> if (event.isAltPressed) null else actionKeys[event.key]?.let { CommitEditorAction.SetAction(state.selected, it) }
    } ?: return false

    onAction(action)

    return true
}

private val actionKeys = mapOf(
    Key.P to PlanAction.PICK,
    Key.R to PlanAction.REWORD,
    Key.S to PlanAction.SQUASH,
    Key.F to PlanAction.FIXUP,
    Key.E to PlanAction.EDIT,
    Key.D to PlanAction.DROP,
)

/** Whether [row] is part of a group of more than one commit, which the bar beside it shows. */
private fun isInGroup(state: CommitEditorState, row: PlanRow): Boolean {
    if (row.action == PlanAction.DROP) return false

    val group = state.plan.groups.firstOrNull { group -> group.commits.any { it.hash == row.commit.hash } }

    return group != null && group.members.isNotEmpty()
}

@Composable
private fun EditorHeader(state: CommitEditorState, onAction: (CommitEditorAction) -> Unit) {
    val plan = state.plan
    val line = plan.line
    val count = plan.rows.size
    val startsAtBase = plan.original.firstOrNull()?.hash == line.commits.lastOrNull()?.hash
    val base = line.base

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.commit_editor_title, shortName(line.branch)),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = when {
                    startsAtBase && base != null ->
                        pluralStringResource(Res.plurals.commit_editor_since_base, count, count, shortName(base))

                    !startsAtBase -> {
                        val after = line.commits.getOrNull(line.commits.indexOfFirst { it.hash == plan.original.first().hash } + 1)
                        pluralStringResource(Res.plurals.commit_editor_after_commit, count, count, after?.shortHash.orEmpty())
                    }

                    else -> pluralStringResource(Res.plurals.commit_editor_commits, count, count)
                },
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )

            val onto = state.onto

            if (plan.canMoveOnto && onto != null && base != null) {
                OntoSwitch(
                    ontoLatest = plan.onto != null,
                    latestLabel = pluralStringResource(
                        Res.plurals.commit_editor_onto_latest,
                        onto.newCommits,
                        shortName(onto.onto.base),
                        onto.onto.commit.take(7),
                        onto.newCommits,
                    ),
                    startLabel = stringResource(
                        Res.string.commit_editor_onto_start,
                        plan.original.first().parentsHashes.firstOrNull()?.take(7).orEmpty(),
                    ),
                    onPick = { onAction(CommitEditorAction.SetOnto(it)) },
                )
            }
        }

        Text(
            text = stringResource(Res.string.commit_editor_keys, if (currentOs == OS.MAC) "⌥" else "Alt+"),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

/** "Onto": the latest base, or where the branch started. */
@Composable
private fun OntoSwitch(ontoLatest: Boolean, latestLabel: String, startLabel: String, onPick: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.commit_editor_onto),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        val shape = RoundedCornerShape(6.dp)

        Row(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f), shape),
        ) {
            for ((isLatest, label) in listOf(true to latestLabel, false to startLabel)) {
                val isSelected = isLatest == ontoLatest

                Text(
                    text = label,
                    style = MaterialTheme.typography.caption,
                    color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .semantics { selected = isSelected }
                        .background(if (isSelected) MaterialTheme.colors.primary else Color.Transparent)
                        .handMouseClickable { if (!isSelected) onPick(isLatest) }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * "2 fixup! commits can go under the commits they name." with a "Put them there" button right after the text, in the
 * strip of Leaf's notices, as the operation banner: in the selection's blue it read as a selected row, and a link at the
 * strip's far end went unseen in a wide window.
 */
@Composable
private fun FixupsStrip(count: Int, onPlace: () -> Unit) {
    val strip = MaterialTheme.styleColors.warningStrip

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(strip.background)
            .bottomEdge(strip.border)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.info),
            contentDescription = null,
            tint = strip.icon,
            modifier = Modifier.size(20.dp),
        )

        Text(
            text = pluralStringResource(Res.plurals.commit_editor_fixups, count, count),
            style = MaterialTheme.typography.body1,
            color = strip.content,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp),
        )

        SecondaryButton(
            text = stringResource(Res.string.commit_editor_fixups_place),
            colors = strip.button,
            onClick = onPlace,
        )
    }
}

@Composable
private fun PlanRowView(
    row: PlanRow,
    marks: PlanRowMarks,
    isSelected: Boolean,
    isDragging: Boolean,
    isInGroup: Boolean,
    isFirstKept: Boolean,
    base: String?,
    upstream: String?,
    onClick: () -> Unit,
    onAction: (PlanAction) -> Unit,
) {
    val action = row.action
    val isDropped = action == PlanAction.DROP
    val isMember = action.joins && !marks.isPickedAlone
    val colors = MaterialTheme.colors
    val background = when {
        isDragging -> colors.backgroundSelected.copy(alpha = 0.9f)
        isSelected -> colors.backgroundSelected
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(background)
            .semantics(mergeDescendants = false) { selected = isSelected }
            .handMouseClickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InstantTooltip(text = stringResource(Res.string.commit_editor_drag)) {
            Icon(
                painter = painterResource(Res.drawable.drag),
                contentDescription = stringResource(Res.string.commit_editor_drag),
                tint = colors.onBackgroundSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }

        ActionPill(
            commit = row.commit.shortHash,
            action = action,
            canJoin = !isFirstKept,
            onAction = onAction,
            onOpen = onClick,
        )

        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isInGroup) groupColor() else Color.Transparent),
        )

        Text(
            text = row.commit.shortHash,
            fontFamily = monoTypography(),
            fontSize = 11.5.sp,
            color = colors.onBackgroundSecondary.copy(alpha = if (isDropped) 0.45f else 1f),
            maxLines = 1,
        )

        Text(
            text = row.commit.shortMessage,
            style = MaterialTheme.typography.body2,
            color = if (isDropped) colors.onBackgroundSecondary else colors.onBackground,
            textDecoration = if (isDropped) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (isMember) 16.dp else 0.dp),
        )

        if (marks.joinedBy > 0 && !isDropped) {
            Tag(
                text = stringResource(Res.string.commit_editor_tag_joined, marks.joinedBy),
                tooltip = pluralStringResource(Res.plurals.commit_editor_tag_joined_tooltip, marks.joinedBy, marks.joinedBy),
                kind = TagKind.GROUP,
            )
        }

        if (marks.isPickedAlone) {
            Tag(
                text = stringResource(Res.string.commit_editor_tag_picked),
                tooltip = stringResource(Res.string.commit_editor_tag_picked_tooltip),
                kind = TagKind.NOTE,
            )
        }

        if (marks.isMoved) {
            Tag(
                text = stringResource(Res.string.commit_editor_tag_moved),
                tooltip = stringResource(Res.string.commit_editor_tag_moved_tooltip),
                kind = TagKind.NOTE,
            )
        }

        if (marks.isEmpty) {
            Tag(
                text = if (base != null) {
                    stringResource(Res.string.commit_editor_tag_empty_in, shortName(base))
                } else {
                    stringResource(Res.string.commit_editor_tag_empty)
                },
                tooltip = stringResource(Res.string.commit_editor_tag_empty_tooltip),
                kind = TagKind.MUTED,
            )
        }

        marks.stopFiles.firstOrNull()?.let { file ->
            Tag(
                text = stringResource(Res.string.commit_editor_tag_stops, file.substringAfterLast('/')),
                tooltip = stringResource(Res.string.commit_editor_tag_stops_tooltip, marks.stopFiles.joinToString(", ")),
                kind = TagKind.STOP,
            )
        }

        if (marks.isPushed && upstream != null) {
            InstantTooltip(text = stringResource(Res.string.commit_editor_pushed_tooltip, shortName(upstream))) {
                Icon(
                    painter = painterResource(Res.drawable.cloud),
                    contentDescription = stringResource(Res.string.commit_editor_pushed_tooltip, shortName(upstream)),
                    tint = colors.onBackgroundSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Text(
            text = row.commit.date.toSmartSystemString(),
            style = MaterialTheme.typography.body2,
            color = colors.onBackgroundSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(90.dp),
        )
    }
}

/** The action's button, which opens its menu: Pick, Reword, Squash, Fixup, Edit, Drop. */
@Composable
private fun ActionPill(
    commit: String,
    action: PlanAction,
    canJoin: Boolean,
    onAction: (PlanAction) -> Unit,
    onOpen: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val look = pillLook(action)
    val shape = RoundedCornerShape(4.dp)
    val description = stringResource(Res.string.commit_editor_action_of, commit)

    Box {
        Row(
            modifier = Modifier
                .width(PILL_WIDTH)
                .height(22.dp)
                .clip(shape)
                .background(look.fill, shape)
                .border(1.dp, look.outline, shape)
                .semantics { contentDescription = description }
                .handOnHover()
                .handMouseClickable {
                    onOpen()
                    isMenuOpen = true
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = actionName(action),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = look.content,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            Icon(
                painter = painterResource(Res.drawable.expand_more),
                contentDescription = null,
                tint = look.content.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp),
            )
        }

        if (isMenuOpen) {
            val nothingBelow = stringResource(Res.string.commit_editor_menu_nothing_below)
            val items = PlanAction.entries.map { option ->
                val isEnabled = canJoin || !option.joins

                SortMenuItem.Option(
                    label = actionMenuName(option),
                    isChecked = option == action,
                    hint = if (isEnabled) null else nothingBelow,
                    shortcut = actionKey(option),
                    closesMenu = true,
                    isEnabled = isEnabled,
                    onClick = { onAction(option) },
                )
            }

            SortMenuPopup(items = items, onDismissRequest = { isMenuOpen = false })
        }
    }
}

/** What a tag beside a row's subject is about, which sets its colours. */
private enum class TagKind { GROUP, NOTE, STOP, MUTED }

@Composable
private fun Tag(text: String, tooltip: String, kind: TagKind) {
    val isDark = MaterialTheme.colors.isDark
    val (fill, content) = when (kind) {
        TagKind.GROUP -> if (isDark) Color(0xFF2E1F52) to Color(0xFFC4B5FD) else Color(0xFFEDE7FB) to Color(0xFF5B21B6)
        TagKind.NOTE -> if (isDark) Color(0xFF3A2C0C) to Color(0xFFFFD27F) else Color(0xFFFFF4E0) to Color(0xFF8A5C00)
        TagKind.STOP -> if (isDark) Color(0xFF3A2C0C) to Color(0xFFFFB638) else Color(0xFFFFE9C2) to Color(0xFF7A4F00)
        TagKind.MUTED -> MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.15f) to MaterialTheme.colors.onBackgroundSecondary
    }

    InstantTooltip(text = tooltip) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            fontWeight = if (kind == TagKind.STOP) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(fill)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** The colours of an action's button: a fill for what changes a commit, an outline for what keeps or leaves it. */
private data class PillLook(val fill: Color, val outline: Color, val content: Color)

@Composable
private fun pillLook(action: PlanAction): PillLook {
    val colors = MaterialTheme.colors
    val isDark = colors.isDark

    return when (action) {
        PlanAction.PICK -> PillLook(Color.Transparent, colors.onBackgroundSecondary.copy(alpha = 0.4f), colors.onBackgroundSecondary)
        PlanAction.REWORD -> PillLook(colors.primary, colors.primary, colors.onPrimary)
        PlanAction.SQUASH -> PillLook(groupColor(), groupColor(), Color.White)
        PlanAction.FIXUP -> if (isDark) {
            PillLook(Color.Transparent, Color(0xFFA78BFA), Color(0xFFC4B5FD))
        } else {
            PillLook(Color.Transparent, Color(0xFF8B5CF6), Color(0xFF5B21B6))
        }

        PlanAction.EDIT -> PillLook(Color(0xFFFFB638), Color(0xFFFFB638), Color(0xFF0E1621))
        PlanAction.DROP -> if (isDark) {
            PillLook(Color.Transparent, Color(0xFFC93838), Color(0xFFFF8A80))
        } else {
            PillLook(Color.Transparent, Color(0xFFC93838), Color(0xFFB42323))
        }
    }
}

/** The purple of rows joined into one commit: the bar beside them and Squash's button. */
@Composable
internal fun groupColor(): Color = if (MaterialTheme.colors.isDark) Color(0xFF6D28D9) else Color(0xFF7C3AED)

@Composable
internal fun actionName(action: PlanAction): String = stringResource(
    when (action) {
        PlanAction.PICK -> Res.string.commit_editor_action_pick
        PlanAction.REWORD -> Res.string.commit_editor_action_reword
        PlanAction.SQUASH -> Res.string.commit_editor_action_squash
        PlanAction.FIXUP -> Res.string.commit_editor_action_fixup
        PlanAction.EDIT -> Res.string.commit_editor_action_edit
        PlanAction.DROP -> Res.string.commit_editor_action_drop
    }
)

@Composable
private fun actionMenuName(action: PlanAction): String = when (action) {
    PlanAction.SQUASH -> stringResource(Res.string.commit_editor_menu_squash)
    PlanAction.FIXUP -> stringResource(Res.string.commit_editor_menu_fixup)
    PlanAction.EDIT -> stringResource(Res.string.commit_editor_menu_edit)
    else -> actionName(action)
}

private fun actionKey(action: PlanAction): String = when (action) {
    PlanAction.PICK -> "P"
    PlanAction.REWORD -> "R"
    PlanAction.SQUASH -> "S"
    PlanAction.FIXUP -> "F"
    PlanAction.EDIT -> "E"
    PlanAction.DROP -> "D"
}

@Composable
private fun EditorFooter(state: CommitEditorState, onAction: (CommitEditorAction) -> Unit) {
    val footer = state.footer
    val (counts, tags) = planSummary(footer)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = counts,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
            )

            if (tags.isNotEmpty()) {
                Text(
                    text = tags,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        }

        if (footer.notes.isNotEmpty()) {
            PlanNotes(footer.notes, branch = shortName(state.plan.line.branch))
        }

        footer.autostashCount?.let { count ->
            AutostashBox(count, state.autostash, onCheckedChange = { onAction(CommitEditorAction.SetAutostash(it)) })
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Outlined, without a role's colours, which made them red in Classic, which draws Unstage red
            OutlinedButton(
                text = stringResource(Res.string.commit_editor_reset),
                onClick = { onAction(CommitEditorAction.Reset) },
            )

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                text = stringResource(Res.string.commit_editor_cancel),
                onClick = { onAction(CommitEditorAction.Cancel) },
            )

            PrimaryButton(
                text = startLabel(footer),
                enabled = footer.canStart,
                onClick = { onAction(CommitEditorAction.Start) },
            )
        }
    }
}

/**
 * A button with no fill and a 1 dp line in the secondary text's colour, which every style holds at 4.5 : 1 on the
 * background: without the line, Reset the plan read as plain text.
 */
@Composable
private fun OutlinedButton(text: String, onClick: () -> Unit) {
    SecondaryButton(
        text = text,
        colors = RoleColors(
            background = Color.Transparent,
            content = MaterialTheme.colors.onBackground,
            border = MaterialTheme.colors.onBackgroundSecondary,
        ),
        onClick = onClick,
    )
}
