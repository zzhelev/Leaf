// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.UnreadableStyle
import dev.app.leaf.domain.styles.UnreadableStyleFile
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.StripColors
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.roleBorder
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.styles.StylePalette
import dev.app.leaf.theme.styles.resolveStyleSide
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.dialogs.abbreviateHome
import dev.app.leaf.ui.styles.StyleEditorStart
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Four cards a row fit the settings' 768 dp. */
private const val COLUMNS = 4
private val CARD_GAP = 14.dp
private val PREVIEW_HEIGHT = 96.dp
private val PREVIEW_SHAPE = RoundedCornerShape(8.dp)

/** Two rows of cards and a glimpse of the third, which says there are more. */
private val GALLERY_MAX_HEIGHT = 330.dp

/** What happened to the last import or export, which stays until dismissed (fork-only). */
sealed interface StyleNotice {
    data class Imported(val name: String) : StyleNotice

    data class Exported(val name: String, val path: String) : StyleNotice

    data class Failed(val error: StyleLibraryError) : StyleNotice
}

/** What the gallery asks for. Export and Import come with the path that the user picked. */
sealed interface StyleGalleryAction {
    data class Select(val style: StyleDefinition) : StyleGalleryAction

    data class Export(val style: StyleDefinition, val path: String) : StyleGalleryAction

    /** After the user confirmed it. */
    data class Delete(val style: StyleDefinition) : StyleGalleryAction

    data class Import(val path: String) : StyleGalleryAction

    data object DismissNotice : StyleGalleryAction
}

@Immutable
data class StyleGalleryState(
    val styles: List<StyleDefinition>,
    val selectedId: String,
    val unreadable: List<UnreadableStyleFile> = emptyList(),
    val notice: StyleNotice? = null,
)

/** What the gallery asks the dialog around it to do: pick files, confirm a deletion, open the editor. */
class StyleGalleryHost(
    val pickExportPath: (fileName: String) -> String?,
    val pickImportPath: () -> String?,
    val confirmDelete: (style: StyleDefinition, onConfirm: () -> Unit) -> Unit,
    val openEditor: (StyleEditorStart) -> Unit,
)

/**
 * Settings → Appearance → Style (fork-only, option 3b of the "Style Library" board): a card per style, its light side
 * on the left and its dark side on the right, drawn in the style's own worked-out colours, then "+ New style" and
 * Import. A click on a card selects its style; its ⋯ menu edits (a style of the user's), duplicates, exports or deletes
 * it. Editing, duplicating and a new style open the editor. Internal for its test.
 */
@Composable
internal fun StyleGallery(state: StyleGalleryState, host: StyleGalleryHost, onAction: (StyleGalleryAction) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        FieldTitles(stringResource(Res.string.settings_style_title), stringResource(Res.string.settings_style_subtitle))

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            verticalArrangement = Arrangement.spacedBy(CARD_GAP),
            modifier = Modifier.fillMaxWidth().heightIn(max = GALLERY_MAX_HEIGHT),
        ) {
            items(state.styles, key = { it.id }) { style ->
                val name = styleName(style)
                val copyName = stringResource(Res.string.settings_style_copy_name, name)
                val menuItems = buildList {
                    if (!style.isBuiltIn) {
                        add(menuOption(stringResource(Res.string.settings_style_edit)) {
                            host.openEditor(StyleEditorStart.Edit(style))
                        })
                    }
                    add(menuOption(stringResource(Res.string.settings_style_duplicate)) {
                        host.openEditor(StyleEditorStart.From(style, copyName, name))
                    })
                    add(menuOption(stringResource(Res.string.settings_style_export)) {
                        host.pickExportPath(exportFileName(name))?.let { onAction(StyleGalleryAction.Export(style, it)) }
                    })
                    if (!style.isBuiltIn) {
                        add(SortMenuItem.Divider)
                        add(menuOption(stringResource(Res.string.settings_style_delete), isDestructive = true) {
                            host.confirmDelete(style) { onAction(StyleGalleryAction.Delete(style)) }
                        })
                    }
                }

                StyleCard(
                    style = style,
                    name = name,
                    isSelected = style.id == state.selectedId,
                    menuItems = menuItems,
                    onSelect = { onAction(StyleGalleryAction.Select(style)) },
                )
            }

            item(key = "new") {
                val selected = state.styles.firstOrNull { it.id == state.selectedId } ?: state.styles.first()
                val selectedName = styleName(selected)
                val newName = stringResource(Res.string.settings_style_new_name)

                DashedCard(
                    label = stringResource(Res.string.settings_style_new),
                    hint = stringResource(Res.string.settings_style_new_hint, selectedName),
                    onClick = { host.openEditor(StyleEditorStart.From(selected, newName, selectedName)) },
                )
            }

            item(key = "import") {
                DashedCard(
                    label = stringResource(Res.string.settings_style_import),
                    hint = stringResource(Res.string.settings_style_import_hint),
                    onClick = { host.pickImportPath()?.let { onAction(StyleGalleryAction.Import(it)) } },
                )
            }
        }

        if (state.unreadable.isNotEmpty()) {
            UnreadableFiles(state.unreadable)
        }

        state.notice?.let { notice ->
            NoticeStrip(notice, onDismiss = { onAction(StyleGalleryAction.DismissNotice) })
        }
    }
}

/** The name an exported style's file gets: the style's, without what file systems refuse. */
internal fun exportFileName(styleName: String): String =
    styleName.replace(Regex("[/\\\\:*?\"<>|]+"), "-").trim().trim('-').trim().ifEmpty { "style" } + ".json"

private fun menuOption(label: String, isDestructive: Boolean = false, onClick: () -> Unit) = SortMenuItem.Option(
    label = label,
    isChecked = false,
    closesMenu = true,
    isDestructive = isDestructive,
    onClick = onClick,
)

@Composable
private fun StyleCard(
    style: StyleDefinition,
    name: String,
    isSelected: Boolean,
    menuItems: List<SortMenuItem>,
    onSelect: () -> Unit,
) {
    val light = remember(style) { resolveStyleSide(style, isLight = true).palette }
    val dark = remember(style) { resolveStyleSide(style, isLight = false).palette }
    val ring = if (isSelected) MaterialTheme.colors.primaryVariant else Color.Transparent

    Column(modifier = Modifier.fillMaxWidth()) {
        // The ring sits 3 dp outside the preview, which keeps its place when it isn't drawn
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, ring, RoundedCornerShape(11.dp))
                .padding(3.dp)
                .semantics {
                    role = Role.RadioButton
                    selected = isSelected
                }
                .handMouseClickable(onClick = onSelect),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clip(PREVIEW_SHAPE)
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.15f), PREVIEW_SHAPE),
            ) {
                PreviewSide(light, Modifier.weight(1f))
                PreviewSide(dark, Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 3.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name takes what the tag leaves, and is cut only past that
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (style.isBuiltIn) {
                    Text(
                        text = stringResource(Res.string.settings_style_built_in),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            CardMenuButton(menuItems)
        }
    }
}

/**
 * One side of a card, after the design's mini preview: the background, the warning strip along the top, a destructive
 * and an action button, two lines of text, and the main action at the bottom.
 */
@Composable
private fun PreviewSide(palette: StylePalette, modifier: Modifier) {
    val pillShape = RoundedCornerShape(3.dp)

    fun Modifier.pill(fill: StyleSlot, edge: StyleSlot) =
        this.clip(pillShape).background(palette[fill]).roleBorder(palette[edge], pillShape)

    Column(modifier = modifier.background(palette[StyleSlot.BACKGROUND]).padding(bottom = 7.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(palette[StyleSlot.WARNING_STRIP_FILL])
                .drawBehind {
                    val edge = palette[StyleSlot.WARNING_STRIP_EDGE]
                    if (edge.alpha > 0f) drawRect(edge, topLeft = Offset(0f, size.height - 1f))
                },
        )

        Row(
            modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 5.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.weight(1f).height(10.dp).pill(StyleSlot.DESTRUCTIVE_FILL, StyleSlot.DESTRUCTIVE_EDGE))
            Box(Modifier.weight(1f).height(10.dp).pill(StyleSlot.ACTION_FILL, StyleSlot.ACTION_EDGE))
        }

        val text = palette[StyleSlot.TEXT].copy(alpha = 0.55f)
        Box(Modifier.padding(start = 7.dp, end = 7.dp, top = 5.dp).fillMaxWidth().height(4.dp).clip(pillShape).background(text))
        Box(Modifier.padding(start = 7.dp, end = 35.dp, top = 5.dp).fillMaxWidth().height(4.dp).clip(pillShape).background(text))

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .padding(horizontal = 7.dp)
                .fillMaxWidth()
                .height(12.dp)
                .clip(pillShape)
                .background(palette[StyleSlot.MAIN_ACTION_FILL]),
        )
    }
}

@Composable
private fun CardMenuButton(items: List<SortMenuItem>) {
    var showMenu by remember { mutableStateOf(false) }
    val tooltip = stringResource(Res.string.settings_style_actions)

    Box {
        DelayedTooltip(tooltip) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .handMouseClickable { showMenu = !showMenu },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.more_horiz),
                    contentDescription = tooltip,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        }

        if (showMenu) {
            SortMenuPopup(items = items, onDismissRequest = { showMenu = false })
        }
    }
}

@Composable
private fun DashedCard(label: String, hint: String, onClick: () -> Unit) {
    val dash = MaterialTheme.colors.onBackground.copy(alpha = 0.3f)

    Box(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
            }
            .clip(PREVIEW_SHAPE)
            .handMouseClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.primaryVariant,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

@Composable
private fun UnreadableFiles(files: List<UnreadableStyleFile>) {
    val reasons = files.map { unreadableText(it) }.joinToString("\n")

    DelayedTooltip(reasons, modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = stringResource(Res.string.settings_style_unreadable, files.joinToString(", ") { it.fileName }),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun unreadableText(file: UnreadableStyleFile): String = when (file.reason) {
    UnreadableStyle.NOT_JSON -> stringResource(Res.string.settings_style_reason_not_json, file.fileName)
    UnreadableStyle.NOT_A_STYLE -> stringResource(Res.string.settings_style_reason_not_a_style, file.fileName)
    UnreadableStyle.BUILT_IN_ID -> stringResource(Res.string.settings_style_reason_built_in_id, file.fileName)
    UnreadableStyle.CANT_READ -> stringResource(Res.string.settings_style_reason_cant_read, file.fileName)
}

@Composable
internal fun noticeText(notice: StyleNotice): String = when (notice) {
    is StyleNotice.Imported -> stringResource(Res.string.settings_style_imported, notice.name)
    is StyleNotice.Exported -> stringResource(Res.string.settings_style_exported, notice.name, abbreviateHome(notice.path))
    is StyleNotice.Failed -> when (val error = notice.error) {
        is StyleLibraryError.NotAStyle -> stringResource(
            Res.string.settings_style_not_imported,
            unreadableText(UnreadableStyleFile(error.fileName, error.reason, error.detail)),
        )
        is StyleLibraryError.CantRead -> stringResource(Res.string.settings_style_cant_read, abbreviateHome(error.path), error.message)
        is StyleLibraryError.CantWrite -> stringResource(Res.string.settings_style_cant_write, abbreviateHome(error.path), error.message)
        is StyleLibraryError.BuiltIn -> stringResource(Res.string.settings_style_is_built_in, error.id)
    }
}

@Composable
private fun NoticeStrip(notice: StyleNotice, onDismiss: () -> Unit) {
    val strip: StripColors = if (notice is StyleNotice.Failed) {
        MaterialTheme.styleColors.warningStrip
    } else {
        MaterialTheme.styleColors.successStrip
    }
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(strip.background)
            .roleBorder(strip.border, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = noticeText(notice),
            style = MaterialTheme.typography.body2,
            color = strip.content,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = stringResource(Res.string.settings_style_dismiss),
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = strip.content,
            modifier = Modifier
                .clip(shape)
                .handMouseClickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
