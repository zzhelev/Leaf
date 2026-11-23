// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.editorName
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keybindings
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.DropDownMenu
import dev.app.leaf.ui.context_menu.addContextMenu
import dev.app.leaf.ui.context_menu.menuIconTint
import dev.app.leaf.ui.getParts
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the file menus need to open a file of the tab's working tree in an editor and show it in the file manager
 * (fork-only): [choice] is the repository's editor, which "Open in …" names. Paths are relative to the working tree.
 */
@Immutable
class FileOpening(
    val choice: EditorChoice,
    val onOpen: (path: String, line: Int?) -> Unit,
    val onChooseEditor: (path: String, line: Int?) -> Unit,
    val onShow: (path: String) -> Unit,
    /** Fork-only, B2: edits an Unstaged file whole in Leaf (`docs/fork/inline-editing.md`). */
    val onEditWholeFile: ((path: String) -> Unit)? = null,
)

/** Provided by the tab's page, so that the menus of upstream's lists don't need another parameter each. */
val LocalFileOpening = compositionLocalOf<FileOpening?> { null }

/** "Show in Finder" on macOS, "Show in Explorer" on Windows, "Show in file manager" elsewhere. */
fun showInFileManagerLabel(os: OS = currentOs): StringResource = when (os) {
    OS.MAC -> Res.string.editor_show_in_finder
    OS.WINDOWS -> Res.string.editor_show_in_explorer
    else -> Res.string.editor_show_in_file_manager
}

/** What the item that opens a file in [this] choice says, at [line] when there is one and the editor takes lines. */
@Composable
fun EditorChoice.openLabel(line: Int?): String {
    val name = editorName

    return when {
        name != null && line != null -> stringResource(Res.string.editor_open_in_at_line, name, line)
        name != null -> stringResource(Res.string.editor_open_in, name)
        this == EditorChoice.DefaultApp -> stringResource(Res.string.editor_open_with_default_app)
        this is EditorChoice.Custom && line != null -> stringResource(Res.string.editor_open_in_editor_at_line, line)
        this is EditorChoice.Custom -> stringResource(Res.string.editor_open_in_editor)
        else -> stringResource(Res.string.editor_choose)
    }
}

/** Which file the shortcut that opens a file in the editor opens (fork-only). */
enum class OpenInEditorTarget {
    /** The shown diff's, which the tab's keys open. */
    DIFF,

    /** Blame's, which blame's own keys open: the tab's keys leave the shortcut to it. */
    BLAME,

    /** None: the conflict resolver or file history covers the diff. */
    NONE,
}

/** What the shortcut opens, by what covers the diff pane. */
fun openInEditorTarget(
    isResolverShown: Boolean,
    isHistoryShown: Boolean,
    isBlameExpanded: Boolean,
    hasDiffFile: Boolean,
): OpenInEditorTarget = when {
    isResolverShown || isHistoryShown -> OpenInEditorTarget.NONE
    isBlameExpanded -> OpenInEditorTarget.BLAME
    hasDiffFile -> OpenInEditorTarget.DIFF
    else -> OpenInEditorTarget.NONE
}

/** The shortcut that opens the shown diff's file, and blame's, as menus write it: "⌘+E", "Ctrl+E". */
fun openInEditorShortcut(): String =
    keybindings[KeybindingOption.OPEN_IN_EDITOR]?.firstOrNull()?.let { getParts(it).joinToString("+") }.orEmpty()

/** The icon of the item that opens a file in [choice]: its editor's own icon, else the box with an arrow. */
@Composable
fun EditorMenuIcon(choice: EditorChoice) {
    AppIconOrOpenIcon(
        iconFile = (choice as? EditorChoice.Installed)?.editor?.icon,
        tint = menuIconTint(),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The items at the top of a file's menu, then a separator: "Open in …" and "Open in another editor…" while the working
 * tree has the file ([canOpen]), and "Show in Finder". Without [opening] (outside a tab), "Show in Finder" runs
 * [onShowWithoutOpening].
 */
fun MutableList<ContextMenuElement>.addFileOpeningItems(
    opening: FileOpening?,
    path: String,
    canOpen: Boolean,
    onShowWithoutOpening: () -> Unit,
    /** Fork-only, B2: an Unstaged text file, which "Edit the whole file" edits in Leaf. */
    canEditWholeFile: Boolean = false,
) {
    val editWholeFile = opening?.onEditWholeFile

    if (canEditWholeFile && editWholeFile != null) {
        addEditWholeFileItem(onClick = { editWholeFile(path) }, tooLong = false)
    }

    if (opening != null) {
        addOpeningItems(opening, path, line = null, canOpen = canOpen, withShortcut = false)
    } else {
        addContextMenu(
            composableLabel = { stringResource(showInFileManagerLabel()) },
            icon = { painterResource(Res.drawable.folder_open) },
            onClick = onShowWithoutOpening,
        )
    }

    add(ContextMenuElement.ContextSeparator)
}

/**
 * "Open in …" at [line], "Open in another editor…" (both while the working tree has the file, [canOpen]) and "Show in
 * Finder". [withShortcut] shows the shortcut that opens the file too, which is true only where it opens this file: the
 * diff's and blame's headers.
 */
private fun MutableList<ContextMenuElement>.addOpeningItems(
    opening: FileOpening,
    path: String,
    line: Int?,
    canOpen: Boolean,
    withShortcut: Boolean,
) {
    if (canOpen) {
        val choice = opening.choice
        val shortcut = openInEditorShortcut().takeIf { withShortcut && it.isNotEmpty() }

        addContextMenu(
            composableLabel = { choice.openLabel(line) },
            customIcon = { EditorMenuIcon(choice) },
            onClick = { opening.onOpen(path, line) },
            hint = shortcut?.let { { it } },
        )

        // Nothing chosen yet: the item above asks which editor
        if (choice != EditorChoice.None) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.editor_open_in_another) },
                icon = { painterResource(Res.drawable.open_in_new) },
                onClick = { opening.onChooseEditor(path, line) },
            )
        }
    }

    addContextMenu(
        composableLabel = { stringResource(showInFileManagerLabel()) },
        icon = { painterResource(Res.drawable.folder_open) },
        onClick = { opening.onShow(path) },
    )
}

/**
 * The items of a header's file name ([FileNameMenu]); with [onEditWholeFile], "Edit the whole file" first, which a file
 * too long for the editor ([tooLong]) has greyed out.
 */
fun headerFileItems(
    opening: FileOpening,
    path: String,
    line: Int?,
    canOpen: Boolean,
    onEditWholeFile: (() -> Unit)? = null,
    tooLong: Boolean = false,
): List<ContextMenuElement> = buildList {
    if (onEditWholeFile != null) {
        addEditWholeFileItem(onEditWholeFile, tooLong)
        add(ContextMenuElement.ContextSeparator)
    }

    addOpeningItems(opening, path, line, canOpen, withShortcut = true)
}

/** "Edit the whole file" (fork-only, B2), greyed out with why for a file too long for the editor. */
private fun MutableList<ContextMenuElement>.addEditWholeFileItem(onClick: () -> Unit, tooLong: Boolean) {
    addContextMenu(
        composableLabel = { stringResource(Res.string.file_edit_whole) },
        icon = { painterResource(Res.drawable.edit) },
        onClick = onClick,
        hint = if (tooLong) ({ stringResource(Res.string.file_edit_too_long) }) else null,
        isEnabled = !tooLong,
    )
}

/** A diff line's item that opens [path] at [line] in the repository's editor. */
fun MutableList<ContextMenuElement>.addOpenAtLineItem(opening: FileOpening, path: String, line: Int) {
    val choice = opening.choice

    addContextMenu(
        composableLabel = { choice.openLabel(line) },
        customIcon = { EditorMenuIcon(choice) },
        onClick = { opening.onOpen(path, line) },
    )
}

/**
 * A header's file name (the diff's, blame's) with a ⌄ after it, which opens the file's items: "Open in …" at the line
 * that [line] gives when the menu opens, with the shortcut, "Open in another editor…" and "Show in Finder" (fork-only).
 * [canOpen] is false for a file that the working tree doesn't have, which only "Show in Finder" shows. Outside a tab
 * (no [LocalFileOpening]), the name alone.
 */
@Composable
fun FileNameMenu(
    name: String,
    path: String,
    canOpen: Boolean,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight?,
    modifier: Modifier = Modifier,
    line: () -> Int? = { null },
    /** Fork-only, B2: "Edit the whole file" first in the menu, greyed out when [tooLongToEdit]. */
    onEditWholeFile: (() -> Unit)? = null,
    tooLongToEdit: Boolean = false,
) {
    val opening = LocalFileOpening.current

    if (opening == null) {
        Text(text = name, style = style, color = color, fontWeight = fontWeight, maxLines = 1, modifier = modifier)
        return
    }

    Box(modifier = modifier.clip(RoundedCornerShape(4.dp))) {
        DropDownMenu(items = { headerFileItems(opening, path, line(), canOpen, onEditWholeFile, tooLongToEdit) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 1.dp, end = 2.dp)
                    .semantics { role = Role.DropdownList },
            ) {
                Text(text = name, style = style, color = color, fontWeight = fontWeight, maxLines = 1, softWrap = false)

                Icon(
                    painter = painterResource(Res.drawable.expand_more),
                    contentDescription = stringResource(Res.string.editor_file_menu),
                    tint = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
