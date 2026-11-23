// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.editorName
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keybindings
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.addContextMenu
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

/** The shortcut that opens the shown diff's file, as menus write it: "⌘+E", "Ctrl+E". */
fun openInEditorShortcut(): String =
    keybindings[KeybindingOption.OPEN_IN_EDITOR]?.firstOrNull()?.let { getParts(it).joinToString("+") }.orEmpty()

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
) {
    if (opening != null && canOpen) {
        val choice = opening.choice

        addContextMenu(
            composableLabel = { choice.openLabel(line = null) },
            icon = { painterResource(Res.drawable.code) },
            onClick = { opening.onOpen(path, null) },
        )

        // Nothing chosen yet: the item above asks which editor
        if (choice != EditorChoice.None) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.editor_open_in_another) },
                icon = { painterResource(Res.drawable.open) },
                onClick = { opening.onChooseEditor(path, null) },
            )
        }
    }

    addContextMenu(
        composableLabel = { stringResource(showInFileManagerLabel()) },
        icon = { painterResource(Res.drawable.folder_open) },
        onClick = if (opening != null) {
            { opening.onShow(path) }
        } else {
            onShowWithoutOpening
        },
    )

    add(ContextMenuElement.ContextSeparator)
}

/** A diff line's item that opens [path] at [line] in the repository's editor. */
fun MutableList<ContextMenuElement>.addOpenAtLineItem(opening: FileOpening, path: String, line: Int) {
    addContextMenu(
        composableLabel = { opening.choice.openLabel(line) },
        icon = { painterResource(Res.drawable.code) },
        onClick = { opening.onOpen(path, line) },
    )
}

/** The diff's and blame's header button that opens their file in the editor, at [line] when there is one. */
@Composable
fun OpenInEditorButton(choice: EditorChoice, line: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = choice.openLabel(line)
    val shortcut = openInEditorShortcut()

    DelayedTooltip(if (shortcut.isEmpty()) label else "$label ($shortcut)") {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .handMouseClickable { onClick() }
                .padding(4.dp)
                .semantics { contentDescription = label },
        ) {
            Icon(
                painter = painterResource(Res.drawable.code),
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
