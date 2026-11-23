// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.editor.DEFAULT_APP_EDITOR
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.repositoryopen.EditorPickerState
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.components.menu.MENU_WINDOW_MARGIN
import dev.app.leaf.ui.components.menu.menuPositionAt
import dev.app.leaf.ui.context_menu.lastContextMenuPosition
import dev.app.leaf.ui.dialogs.PickRow
import dev.app.leaf.ui.dialogs.branchChoicesKeys
import org.jetbrains.compose.resources.stringResource
import java.io.File

private val PICKER_WIDTH = 300.dp

/** A row of the picker: an editor that Leaf found, or the default app. */
private data class PickerRow(val id: String, val editor: InstalledEditor?)

/**
 * The picker of "Open in another editor…" (fork-only): where the file's menu was, or in the
 * window's middle when nothing opened a menu.
 */
@Composable
fun EditorPickerPopup(
    state: EditorPickerState,
    onPick: (editorId: String, remember: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val anchor = remember(state) { lastContextMenuPosition.takeIf { state.isAtMenu } }
    val margin = with(LocalDensity.current) { MENU_WINDOW_MARGIN.roundToPx() }

    Popup(
        properties = PopupProperties(focusable = true),
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val point = anchor ?: IntOffset(
                    (windowSize.width - popupContentSize.width) / 2,
                    (windowSize.height - popupContentSize.height) / 3,
                )

                return menuPositionAt(point, windowSize, popupContentSize, margin)
            }
        },
        onDismissRequest = onClose,
    ) {
        EditorPickerList(state, onPick, onClose)
    }
}

/** The picker's list, which [EditorPickerPopup] shows: the editors, the default app, and whether to keep the choice. */
@Composable
fun EditorPickerList(
    state: EditorPickerState,
    onPick: (editorId: String, remember: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val rows = remember(state) {
        state.editors.map { PickerRow(it.id, it) } + PickerRow(DEFAULT_APP_EDITOR, editor = null)
    }
    var highlighted by remember(state) { mutableStateOf(-1) }
    // Without a default editor, the first pick becomes it; with one, keeping another is the repository's own choice
    var keepChoice by remember(state) { mutableStateOf(state.defaultEditor == null) }
    val focusRequester = remember { FocusRequester() }
    val pick: (Int) -> Unit = { index -> rows.getOrNull(index)?.let { onPick(it.id, keepChoice) } }

    LaunchedEffect(state) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .dropShadow(
                shape = RoundedCornerShape(4.dp),
                shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
            )
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colors.background)
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), MaterialTheme.shapes.small)
            .width(PICKER_WIDTH)
            .padding(vertical = 4.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .branchChoicesKeys(
                highlighted = highlighted,
                rowCount = rows.size,
                onHighlight = { highlighted = it },
                onPick = pick,
            )
            .focusable()
            .testTag("editor-picker"),
    ) {
        if (state.editors.isEmpty()) {
            Text(
                text = stringResource(Res.string.editor_picker_none_found),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        rows.forEachIndexed { index, row ->
            if (row.editor == null) {
                Separator()
            }

            PickRow(
                name = row.editor?.name ?: defaultAppLabel(state.path),
                matchStart = 0,
                matchLength = 0,
                isHighlighted = index == highlighted,
                onPick = { pick(index) },
                leading = { EditorIcon(row.editor) },
                trailing = {
                    if (row.id == state.defaultEditor) {
                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = stringResource(Res.string.editor_picker_default),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                        )
                    }
                },
            )
        }

        Separator()

        Box(modifier = Modifier.padding(horizontal = 4.dp)) {
            CheckboxText(
                value = keepChoice,
                onCheckedChange = { keepChoice = !keepChoice },
                text = if (state.defaultEditor == null || state.repositoryName == null) {
                    stringResource(Res.string.editor_picker_remember_default)
                } else {
                    stringResource(Res.string.editor_picker_remember_repository, state.repositoryName)
                },
            )
        }
    }
}

@Composable
private fun defaultAppLabel(path: String): String {
    val extension = File(path).extension

    return if (extension.isEmpty()) {
        stringResource(Res.string.editor_picker_default_app)
    } else {
        stringResource(Res.string.editor_picker_default_app_for, extension)
    }
}

@Composable
private fun Separator() {
    Spacer(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colors.onBackground.copy(alpha = 0.12f)),
    )
}

/** The editor's own app icon where its app has one, else the box with an arrow, which the default app has too. */
@Composable
private fun EditorIcon(editor: InstalledEditor?) {
    AppIconOrOpenIcon(
        iconFile = editor?.icon,
        tint = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(end = 8.dp).size(16.dp),
    )
}
