// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.highlighting.SyntaxCategory

/** Where a colour is used, which groups the rows of the style editor's Advanced table. */
enum class StyleSlotGroup {
    SURFACES,
    TEXT,
    FILES,
    DIFF,

    /** The colours of code, by category (`docs/fork/syntax-highlighting.md`, 4.4). */
    SYNTAX,
    GRAPH_LANES,
    BUTTONS_AND_NOTICES,
}

/**
 * Every colour that a style sets (fork-only). [key] names it in a style's file, so it never changes: renaming an entry
 * keeps its key, and a key that a file has but this version doesn't know is left out when the file is read.
 *
 * The first groups are the theme's colours (`ColorsScheme`), the last the roles of buttons and notices (`StyleColors`).
 * The theme's `primary` and `onPrimary` are [MAIN_ACTION_FILL] and [MAIN_ACTION_TEXT], as they are in every built-in
 * style.
 */
enum class StyleSlot(val key: String, val group: StyleSlotGroup) {
    BACKGROUND("background", StyleSlotGroup.SURFACES),

    /** The theme's `surface`: the side panel, the status pane, dialogs. */
    PANELS("panels", StyleSlotGroup.SURFACES),

    /** The theme's `secondarySurface`: hunk headers, the boxes in dialogs. */
    BOXES("boxes", StyleSlotGroup.SURFACES),

    /** The theme's `tertiarySurface`: the headers of the lists, the log and the diff. */
    HEADERS("headers", StyleSlotGroup.SURFACES),
    SELECTED_ROW("selectedRow", StyleSlotGroup.SURFACES),
    DIALOG_OVERLAY("dialogOverlay", StyleSlotGroup.SURFACES),
    SCROLLBAR("scrollbar", StyleSlotGroup.SURFACES),
    SCROLLBAR_HOVER("scrollbarHover", StyleSlotGroup.SURFACES),

    TEXT("text", StyleSlotGroup.TEXT),
    SECONDARY_TEXT("secondaryText", StyleSlotGroup.TEXT),

    /** The theme's `primaryVariant`: links, the selected tab's underline, a focused text field. */
    LINKS("links", StyleSlotGroup.TEXT),

    /** The theme's `secondary`: progress text, the "amending" tag, the focused search match. */
    HIGHLIGHT("highlight", StyleSlotGroup.TEXT),
    ON_HIGHLIGHT("onHighlight", StyleSlotGroup.TEXT),

    /** Error boxes and toasts. */
    ERROR("error", StyleSlotGroup.TEXT),
    ON_ERROR("onError", StyleSlotGroup.TEXT),

    ADDED_FILE("addedFile", StyleSlotGroup.FILES),
    MODIFIED_FILE("modifiedFile", StyleSlotGroup.FILES),
    DELETED_FILE("deletedFile", StyleSlotGroup.FILES),
    CONFLICTED_FILE("conflictedFile", StyleSlotGroup.FILES),

    ADDED_LINE("addedLine", StyleSlotGroup.DIFF),
    ADDED_WORDS("addedWords", StyleSlotGroup.DIFF),
    REMOVED_LINE("removedLine", StyleSlotGroup.DIFF),
    REMOVED_WORDS("removedWords", StyleSlotGroup.DIFF),
    KEYWORD("keyword", StyleSlotGroup.SYNTAX),
    ANNOTATION("annotation", StyleSlotGroup.SYNTAX),
    COMMENT("comment", StyleSlotGroup.SYNTAX),
    STRING("string", StyleSlotGroup.SYNTAX),

    /** Numbers and constants: `42`, `true`, `null`. */
    NUMBER("number", StyleSlotGroup.SYNTAX),
    TYPE("type", StyleSlotGroup.SYNTAX),
    FUNCTION("function", StyleSlotGroup.SYNTAX),

    /** Markup's tags: `<div>`, `<resources>`. */
    TAG("tag", StyleSlotGroup.SYNTAX),

    /** Markup's attributes, and the keys of JSON, YAML and CSS. */
    ATTRIBUTE("attribute", StyleSlotGroup.SYNTAX),

    LANE_1("lane1", StyleSlotGroup.GRAPH_LANES),
    LANE_2("lane2", StyleSlotGroup.GRAPH_LANES),
    LANE_3("lane3", StyleSlotGroup.GRAPH_LANES),
    LANE_4("lane4", StyleSlotGroup.GRAPH_LANES),
    LANE_5("lane5", StyleSlotGroup.GRAPH_LANES),
    LANE_6("lane6", StyleSlotGroup.GRAPH_LANES),

    MAIN_ACTION_FILL("mainAction.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    MAIN_ACTION_TEXT("mainAction.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    ACTION_FILL("action.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    ACTION_TEXT("action.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    ACTION_EDGE("action.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    NEUTRAL_FILL("neutral.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    NEUTRAL_TEXT("neutral.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    NEUTRAL_EDGE("neutral.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    DESTRUCTIVE_FILL("destructive.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    DESTRUCTIVE_TEXT("destructive.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    DESTRUCTIVE_EDGE("destructive.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_FILL("warningStrip.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_TEXT("warningStrip.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_ICON("warningStrip.icon", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_EDGE("warningStrip.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_BUTTON_FILL("warningStrip.button.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_BUTTON_TEXT("warningStrip.button.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_STRIP_BUTTON_EDGE("warningStrip.button.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_FILL("successStrip.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_TEXT("successStrip.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_ICON("successStrip.icon", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_EDGE("successStrip.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_BUTTON_FILL("successStrip.button.fill", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_BUTTON_TEXT("successStrip.button.text", StyleSlotGroup.BUTTONS_AND_NOTICES),
    SUCCESS_STRIP_BUTTON_EDGE("successStrip.button.edge", StyleSlotGroup.BUTTONS_AND_NOTICES),
    WARNING_ICON("warningIcon", StyleSlotGroup.BUTTONS_AND_NOTICES);

    companion object {
        private val byKey = entries.associateBy { it.key }

        /** The slot that a file's [key] names, or null for one that this version doesn't know. */
        fun fromKey(key: String): StyleSlot? = byKey[key]

        /** The graph's lanes, in the order the log uses them. */
        val LANES = listOf(LANE_1, LANE_2, LANE_3, LANE_4, LANE_5, LANE_6)

        /** The colours of code, in [SyntaxCategory]'s order. */
        val SYNTAX = listOf(KEYWORD, ANNOTATION, COMMENT, STRING, NUMBER, TYPE, FUNCTION, TAG, ATTRIBUTE)

        /** The colour of code of [category]. */
        fun of(category: SyntaxCategory): StyleSlot = SYNTAX[category.ordinal]
    }
}
