// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.StyleSlot.*
import dev.app.leaf.theme.styles.ContrastCheck
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Which part of a row's thing a colour is, when the row has more than one. */
enum class SlotPart(val label: StringResource) {
    FILL(Res.string.style_editor_part_fill),
    TEXT(Res.string.style_editor_part_text),
    EDGE(Res.string.style_editor_part_edge),
    ICON(Res.string.style_editor_part_icon),
    LINE(Res.string.style_editor_part_line),
    WORDS(Res.string.style_editor_part_words),
}

/** A row of the Advanced table: a thing Leaf draws, and its colours, each with its part when there's more than one. */
class AdvancedRow(
    val label: StringResource,
    val slots: List<Pair<StyleSlot, SlotPart?>>,
    /** The lane's number, for "Lane %1$d". */
    val number: Int? = null,
)

class AdvancedGroup(val title: StringResource, val rows: List<AdvancedRow>)

private fun row(label: StringResource, slot: StyleSlot) = AdvancedRow(label, listOf(slot to null))

private fun row(label: StringResource, vararg slots: Pair<StyleSlot, SlotPart>) = AdvancedRow(label, slots.toList())

/**
 * The Advanced table (fork-only, option 3c of the "Style Library" board): every colour a style sets, once, grouped as
 * Surfaces, Text, Files, Diff, Graph lanes, and Buttons and notices.
 */
val ADVANCED_GROUPS: List<AdvancedGroup> = listOf(
    AdvancedGroup(
        Res.string.style_editor_group_surfaces,
        listOf(
            row(Res.string.style_editor_row_background, BACKGROUND),
            row(Res.string.style_editor_row_panels, PANELS),
            row(Res.string.style_editor_row_boxes, BOXES),
            row(Res.string.style_editor_row_headers, HEADERS),
            row(Res.string.style_editor_row_selected_row, SELECTED_ROW),
            row(Res.string.style_editor_row_dialog_overlay, DIALOG_OVERLAY),
            row(Res.string.style_editor_row_scrollbar, SCROLLBAR),
            row(Res.string.style_editor_row_scrollbar_hover, SCROLLBAR_HOVER),
        ),
    ),
    AdvancedGroup(
        Res.string.style_editor_group_text,
        listOf(
            row(Res.string.style_editor_row_text, TEXT),
            row(Res.string.style_editor_row_secondary_text, SECONDARY_TEXT),
            row(Res.string.style_editor_row_links, LINKS),
            row(Res.string.style_editor_row_highlight, HIGHLIGHT to SlotPart.FILL, ON_HIGHLIGHT to SlotPart.TEXT),
            row(Res.string.style_editor_row_error, ERROR to SlotPart.FILL, ON_ERROR to SlotPart.TEXT),
        ),
    ),
    AdvancedGroup(
        Res.string.style_editor_group_files,
        listOf(
            row(Res.string.style_editor_row_added_file, ADDED_FILE),
            row(Res.string.style_editor_row_modified_file, MODIFIED_FILE),
            row(Res.string.style_editor_row_deleted_file, DELETED_FILE),
            row(Res.string.style_editor_row_conflicted_file, CONFLICTED_FILE),
        ),
    ),
    AdvancedGroup(
        Res.string.style_editor_group_diff,
        listOf(
            row(Res.string.style_editor_row_added_lines, ADDED_LINE to SlotPart.LINE, ADDED_WORDS to SlotPart.WORDS),
            row(Res.string.style_editor_row_removed_lines, REMOVED_LINE to SlotPart.LINE, REMOVED_WORDS to SlotPart.WORDS),
            row(Res.string.style_editor_row_keyword, KEYWORD),
            row(Res.string.style_editor_row_annotation, ANNOTATION),
            row(Res.string.style_editor_row_comment, COMMENT),
        ),
    ),
    AdvancedGroup(
        Res.string.style_editor_group_lanes,
        StyleSlot.LANES.mapIndexed { index, lane ->
            AdvancedRow(Res.string.style_editor_row_lane, listOf(lane to null), number = index + 1)
        },
    ),
    AdvancedGroup(
        Res.string.style_editor_group_buttons,
        listOf(
            row(Res.string.style_editor_row_main_action, MAIN_ACTION_FILL to SlotPart.FILL, MAIN_ACTION_TEXT to SlotPart.TEXT),
            row(Res.string.style_editor_row_action, ACTION_FILL to SlotPart.FILL, ACTION_TEXT to SlotPart.TEXT, ACTION_EDGE to SlotPart.EDGE),
            row(Res.string.style_editor_row_neutral, NEUTRAL_FILL to SlotPart.FILL, NEUTRAL_TEXT to SlotPart.TEXT, NEUTRAL_EDGE to SlotPart.EDGE),
            row(
                Res.string.style_editor_row_destructive,
                DESTRUCTIVE_FILL to SlotPart.FILL, DESTRUCTIVE_TEXT to SlotPart.TEXT, DESTRUCTIVE_EDGE to SlotPart.EDGE,
            ),
            row(
                Res.string.style_editor_row_warning_strip,
                WARNING_STRIP_FILL to SlotPart.FILL, WARNING_STRIP_TEXT to SlotPart.TEXT,
                WARNING_STRIP_ICON to SlotPart.ICON, WARNING_STRIP_EDGE to SlotPart.EDGE,
            ),
            row(
                Res.string.style_editor_row_warning_strip_button,
                WARNING_STRIP_BUTTON_FILL to SlotPart.FILL, WARNING_STRIP_BUTTON_TEXT to SlotPart.TEXT,
                WARNING_STRIP_BUTTON_EDGE to SlotPart.EDGE,
            ),
            row(
                Res.string.style_editor_row_success_strip,
                SUCCESS_STRIP_FILL to SlotPart.FILL, SUCCESS_STRIP_TEXT to SlotPart.TEXT,
                SUCCESS_STRIP_ICON to SlotPart.ICON, SUCCESS_STRIP_EDGE to SlotPart.EDGE,
            ),
            row(
                Res.string.style_editor_row_success_strip_button,
                SUCCESS_STRIP_BUTTON_FILL to SlotPart.FILL, SUCCESS_STRIP_BUTTON_TEXT to SlotPart.TEXT,
                SUCCESS_STRIP_BUTTON_EDGE to SlotPart.EDGE,
            ),
            row(Res.string.style_editor_row_warning_icon, WARNING_ICON),
        ),
    ),
)

/** The row that holds [slot], and the part it is there. */
fun rowOf(slot: StyleSlot): Pair<AdvancedRow, SlotPart?> = ADVANCED_GROUPS.asSequence()
    .flatMap { it.rows }
    .firstNotNullOf { row -> row.slots.firstOrNull { it.first == slot }?.let { row to it.second } }

@Composable
fun rowLabel(row: AdvancedRow): String =
    if (row.number != null) stringResource(row.label, row.number) else stringResource(row.label)

/**
 * [slot]'s name: its row's, and its part when the row has more than one ("Action text"). A line is its row's thing
 * itself: "Added lines", not "Added lines line".
 */
@Composable
fun slotLabel(slot: StyleSlot): String {
    val (row, part) = rowOf(slot)
    val label = rowLabel(row)
    return if (part == null || part == SlotPart.LINE) label else "$label ${stringResource(part.label)}"
}

/**
 * What [check] is drawn on, named: "Headers", or "Action on Headers" for a button's label. A fill or a line is named
 * by its row, as it's the thing itself.
 */
@Composable
fun groundLabel(check: ContrastCheck): String = check.layers
    .filter { it != BACKGROUND || check.layers.size == 1 }
    .map { slot ->
        val (row, part) = rowOf(slot)
        if (part == SlotPart.FILL || part == SlotPart.LINE) rowLabel(row) else slotLabel(slot)
    }
    .joinToString(" on ")
