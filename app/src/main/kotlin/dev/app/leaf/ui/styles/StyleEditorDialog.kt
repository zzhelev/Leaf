// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import dev.app.leaf.ui.dialogs.settings.StyleNotice
import dev.app.leaf.ui.dialogs.settings.exportFileName
import dev.app.leaf.ui.dialogs.settings.noticeText
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val DIALOG_WIDTH = 1060.dp
private val DIALOG_HEIGHT = 680.dp
private val CONTROLS_WIDTH = 400.dp

/** What the editor asks the screen around it to do (fork-only). */
class StyleEditorHost(
    val close: () -> Unit,
    /** Opens [start] in place of this editor. */
    val replaceWith: (StyleEditorStart) -> Unit,
    val confirmDelete: (styleName: String, onConfirm: () -> Unit) -> Unit,
    val confirmDiscard: (styleName: String, onConfirm: () -> Unit) -> Unit,
)

/** The style editor (fork-only, option 3c of the "Style Library" board), over Settings. */
@Composable
fun StyleEditorDialog(viewModel: StyleEditorViewModel, host: StyleEditorHost) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val isDone by viewModel.isDone.collectAsState()

    // Saved or deleted: the gallery shows it
    LaunchedEffect(isDone) {
        if (isDone) host.close()
    }

    val exportTitle = stringResource(Res.string.settings_style_export_title)

    StyleEditorContent(
        state = state,
        message = message,
        onAction = viewModel::onAction,
        host = host,
        pickExportPath = { viewModel.pickExportPath(exportTitle, it) },
    )
}

/** The editor's content from its state, apart from the view model, for its test. */
@Composable
internal fun StyleEditorContent(
    state: StyleEditorState,
    message: StyleEditorMessage?,
    onAction: (StyleEditorAction) -> Unit,
    host: StyleEditorHost,
    pickExportPath: (String) -> String?,
) {
    val resolved = remember(state.draft) { ResolvedDraft(state.draft) }
    val styleName = state.draft.name.ifBlank { state.saved.name }
    val close = {
        if (state.draft != state.saved) host.confirmDiscard(styleName) { host.close() } else host.close()
    }

    MaterialDialog(onCloseRequested = close, paddingHorizontal = 0.dp, paddingVertical = 0.dp) {
        Column(modifier = Modifier.width(DIALOG_WIDTH).height(DIALOG_HEIGHT)) {
            EditorHeader(state, onAction, host, pickExportPath, close, Modifier.dialogDragHandle())

            Row(modifier = Modifier.weight(1f).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(
                    modifier = Modifier.width(CONTROLS_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LookSwitch(state.draft.look, onAction)
                    SeedRows(state, onAction)
                    Advanced(state, resolved, onAction)
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(stringResource(Res.string.style_editor_preview))
                        Text(
                            stringResource(Res.string.style_editor_preview_hint),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                        )
                    }
                    StyleEditorPreview(state.draft, isLight = true)
                    StyleEditorPreview(state.draft, isLight = false)
                }
            }

            EditorFooter(state, message, onAction, close)
        }
    }
}

@Composable
private fun EditorHeader(
    state: StyleEditorState,
    onAction: (StyleEditorAction) -> Unit,
    host: StyleEditorHost,
    pickExportPath: (String) -> String?,
    close: () -> Unit,
    modifier: Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val copyName = stringResource(Res.string.settings_style_copy_name, state.draft.name)

    Row(
        modifier = modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdjustableOutlinedTextField(
            value = state.draft.name,
            onValueChange = { onAction(StyleEditorAction.Rename(it)) },
            hint = stringResource(Res.string.style_editor_name),
            singleLine = true,
            isError = state.draft.name.isBlank(),
            textStyle = MaterialTheme.typography.h4.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colors.onBackground),
            modifier = Modifier.width(280.dp),
        )

        Text(
            text = state.startedFrom?.let { stringResource(Res.string.style_editor_new_from, it) }
                ?: stringResource(Res.string.style_editor_yours),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.weight(1f),
        )

        Box {
            HeaderIcon(Res.drawable.more_horiz, stringResource(Res.string.settings_style_actions)) { showMenu = !showMenu }

            if (showMenu) {
                val items = buildList {
                    add(option(stringResource(Res.string.settings_style_duplicate)) {
                        host.replaceWith(StyleEditorStart.From(state.draft, copyName, state.draft.name))
                    })
                    add(option(stringResource(Res.string.settings_style_export)) {
                        pickExportPath(exportFileName(state.draft.name))?.let { onAction(StyleEditorAction.Export(it)) }
                    })
                    if (!state.isNew) {
                        add(SortMenuItem.Divider)
                        add(option(stringResource(Res.string.style_editor_delete), isDestructive = true) {
                            host.confirmDelete(state.saved.name) { onAction(StyleEditorAction.Delete) }
                        })
                    }
                }
                SortMenuPopup(items = items, onDismissRequest = { showMenu = false })
            }
        }

        HeaderIcon(Res.drawable.close, stringResource(Res.string.style_editor_close), onClick = close)
    }
}

private fun option(label: String, isDestructive: Boolean = false, onClick: () -> Unit) =
    SortMenuItem.Option(label = label, isChecked = false, closesMenu = true, isDestructive = isDestructive, onClick = onClick)

@Composable
private fun HeaderIcon(icon: DrawableResource, description: String, onClick: () -> Unit) {
    DelayedTooltip(description) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).handMouseClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = description,
                tint = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
    )
}

@Composable
private fun LookSwitch(look: AccentStyle, onAction: (StyleEditorAction) -> Unit) {
    val shape = RoundedCornerShape(6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(Res.string.style_editor_look))

        Row(modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), shape)) {
            for (option in listOf(AccentStyle.CLASSIC, AccentStyle.TONAL, AccentStyle.QUIET)) {
                val isSelected = option == look

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isSelected) MaterialTheme.colors.backgroundSelected else Color.Transparent)
                        .semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        }
                        .handMouseClickable { onAction(StyleEditorAction.SetLook(option)) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(lookName(option)),
                        style = MaterialTheme.typography.body2,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colors.onBackground,
                    )
                }
            }
        }
    }
}

private fun lookName(look: AccentStyle) = when (look) {
    AccentStyle.CLASSIC -> Res.string.settings_style_classic
    AccentStyle.TONAL -> Res.string.settings_style_tonal
    AccentStyle.QUIET -> Res.string.settings_style_quiet
}

/** A seed's row: the colour, and what it's for. */
private class SeedRow(val seed: StyleSeed, val title: StringResource, val use: StringResource)

private val SEED_ROWS = listOf(
    SeedRow(StyleSeed.ACCENT, Res.string.style_editor_accent, Res.string.style_editor_accent_use),
    SeedRow(StyleSeed.DANGER, Res.string.style_editor_danger, Res.string.style_editor_danger_use),
    SeedRow(StyleSeed.WARNING, Res.string.style_editor_warning, Res.string.style_editor_warning_use),
    SeedRow(StyleSeed.SUCCESS, Res.string.style_editor_success, Res.string.style_editor_success_use),
)

@Composable
private fun SeedRows(state: StyleEditorState, onAction: (StyleEditorAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(Res.string.style_editor_colours))

        // The background: one colour per side
        val light = state.draft.light.seeds.background
        val dark = state.draft.dark.seeds.background
        SeedLine(
            title = stringResource(Res.string.style_editor_background),
            use = stringResource(Res.string.style_editor_background_use),
            hex = StyleColorHex.format(light) + "\n" + StyleColorHex.format(dark),
        ) {
            for ((isLight, argb) in listOf(true to light, false to dark)) {
                SeedSwatch(
                    target = ColourTarget.Seed(StyleSeed.BACKGROUND, isLight),
                    colors = listOf(argb),
                    title = stringResource(Res.string.style_editor_background) + " · " +
                        stringResource(if (isLight) Res.string.style_editor_light else Res.string.style_editor_dark),
                    use = null,
                    state = state,
                    onPick = { onAction(StyleEditorAction.SetSeed(StyleSeed.BACKGROUND, it, isLight)) },
                    onAction = onAction,
                )
            }
        }

        for (row in SEED_ROWS) {
            val lightSeed = state.draft.light.seeds[row.seed]
            val darkSeed = state.draft.dark.seeds[row.seed]
            val title = stringResource(row.title)
            val use = stringResource(row.use)

            SeedLine(title = title, use = use, hex = StyleColorHex.format(lightSeed)) {
                SeedSwatch(
                    target = ColourTarget.Seed(row.seed),
                    // A copy of a built-in style has a seed per side; a pick sets both
                    colors = if (lightSeed == darkSeed) listOf(lightSeed) else listOf(lightSeed, darkSeed),
                    title = title,
                    use = use,
                    state = state,
                    onPick = { onAction(StyleEditorAction.SetSeed(row.seed, it)) },
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun SeedLine(title: String, use: String, hex: String, swatches: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { swatches() }

        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.body2, fontWeight = FontWeight.Medium, color = MaterialTheme.colors.onBackground)
            Text(use, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onBackgroundSecondary)
        }

        Text(
            hex,
            style = MaterialTheme.typography.caption.copy(fontFamily = monoTypography()),
            color = MaterialTheme.colors.onBackgroundSecondary,
        )
    }
}

@Composable
private fun SeedSwatch(
    target: ColourTarget,
    colors: List<Int>,
    title: String,
    use: String?,
    state: StyleEditorState,
    onPick: (Int) -> Unit,
    onAction: (StyleEditorAction) -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)

    Box {
        Row(
            modifier = Modifier
                .size(28.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.15f), shape)
                .semantics { contentDescription = title }
                .handMouseClickable { onAction(StyleEditorAction.OpenPicker(target)) },
        ) {
            for (argb in colors) Box(Modifier.weight(1f).fillMaxHeight().background(Color(argb)))
        }

        if (state.picker == target) {
            ColourPicker(
                title = title,
                use = use,
                color = colors.first(),
                onPick = onPick,
                onUseWorkedOut = null,
                onDismiss = { onAction(StyleEditorAction.OpenPicker(null)) },
            )
        }
    }
}

@Composable
private fun Advanced(state: StyleEditorState, resolved: ResolvedDraft, onAction: (StyleEditorAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colors.onBackground.copy(alpha = 0.08f)))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .handMouseClickable { onAction(StyleEditorAction.SetAdvancedOpen(!state.isAdvancedOpen)) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(if (state.isAdvancedOpen) Res.drawable.expand_more else Res.drawable.chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(Res.string.style_editor_advanced),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
            )
            Text(
                stringResource(Res.string.style_editor_advanced_hint),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
            )
        }

        if (state.isAdvancedOpen) {
            StyleAdvancedTable(state, resolved, onAction)
        }
    }
}

@Composable
private fun EditorFooter(
    state: StyleEditorState,
    message: StyleEditorMessage?,
    onAction: (StyleEditorAction) -> Unit,
    close: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colors.onBackground.copy(alpha = 0.07f)))

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(stringResource(Res.string.style_editor_revert), enabled = state.draft != state.saved) {
            onAction(StyleEditorAction.Revert)
        }

        Box(modifier = Modifier.weight(1f)) {
            if (message != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = noticeText(message.asNotice(state.draft.name)),
                        style = MaterialTheme.typography.body2,
                        color = if (message is StyleEditorMessage.Failed) MaterialTheme.colors.error else MaterialTheme.colors.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(stringResource(Res.string.settings_style_dismiss)) { onAction(StyleEditorAction.DismissMessage) }
                }
            }
        }

        TextButton(stringResource(Res.string.style_editor_cancel), onClick = close)

        PrimaryButton(
            text = stringResource(Res.string.style_editor_save),
            enabled = state.hasChanges && state.draft.name.isNotBlank(),
            onClick = { onAction(StyleEditorAction.Save) },
        )
    }
}

private fun StyleEditorMessage.asNotice(name: String): StyleNotice = when (this) {
    is StyleEditorMessage.Exported -> StyleNotice.Exported(name, path)
    is StyleEditorMessage.Failed -> StyleNotice.Failed(error)
}

@Composable
private fun TextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.primaryVariant.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .handMouseClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
