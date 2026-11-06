// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.CodeFontChoice
import dev.app.leaf.domain.models.ui.FontSettings
import dev.app.leaf.domain.models.ui.InterfaceFont
import dev.app.leaf.domain.models.ui.bundledFontShows
import dev.app.leaf.domain.models.ui.codeFontChoices
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.BUNDLED_CODE_FONT
import dev.app.leaf.theme.SystemFonts
import dev.app.leaf.theme.SystemInterfaceFont
import dev.app.leaf.theme.bundledMonoTypography
import dev.app.leaf.theme.diffLineAdded
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.inCodeFont
import dev.app.leaf.theme.installedFontFamily
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.syntaxColors
import dev.app.leaf.ui.amberText
import dev.app.leaf.ui.components.menu.MENU_WINDOW_MARGIN
import dev.app.leaf.ui.components.sort.BelowRightAlignedPositionProvider
import dev.app.leaf.ui.dialogs.ChoiceGroupHeader
import dev.app.leaf.ui.dialogs.PickRow
import dev.app.leaf.ui.dialogs.branchChoicesKeys
import dev.app.leaf.ui.dropdowns.DropDownOption
import dev.app.leaf.ui.keepInView
import dev.app.leaf.viewmodels.SettingsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The font rows' controls: wider than the other rows', for fonts' names. */
private val FONT_CONTROL_WIDTH = 240.dp
private val LIST_WIDTH = 280.dp
private val LIST_MAX_HEIGHT = 360.dp
private val MARK_SIZE = 14.dp
private const val REOPEN_GUARD_MS = 300
private const val HEADER_BACKGROUND = 0.06f

/**
 * Settings → Appearance's fonts (fork-only): the interface font, the code font with a preview of a diff in it, and
 * whether code may use ligatures. Each applies at once.
 */
@Composable
internal fun FontRows(fonts: FontSettings, onAction: (SettingsAction) -> Unit) {
    InterfaceFontRow(
        choice = fonts.interfaceFont,
        systemFont = SystemFonts.interfaceFont,
        onPick = { onAction(SettingsAction.SetConfig(AppConfig.InterfaceFont(it))) },
    )

    CodeFontRow(
        codeFont = fonts.codeFont,
        isInstalled = SystemFonts::isInstalled,
        loadInstalled = SystemFonts::monospaced,
        onPick = { onAction(SettingsAction.SetConfig(AppConfig.CodeFont(it))) },
    )

    CodeFontPreview()

    SettingToggle(
        title = stringResource(Res.string.settings_code_ligatures_title),
        subtitle = stringResource(Res.string.settings_code_ligatures_subtitle),
        value = fonts.codeLigatures,
        onValueChanged = { onAction(SettingsAction.SetConfig(AppConfig.CodeLigatures(it))) },
    )
}

/** Inter, or the system's font when Leaf finds one. A choice of System on a system without one still shows. */
@Composable
internal fun InterfaceFontRow(
    choice: InterfaceFont,
    systemFont: SystemInterfaceFont?,
    onPick: (InterfaceFont) -> Unit,
) {
    val options = buildList {
        add(DropDownOption(InterfaceFont.INTER, stringResource(Res.string.settings_interface_font_inter)))

        if (systemFont != null) {
            add(
                DropDownOption(
                    InterfaceFont.SYSTEM,
                    stringResource(Res.string.settings_interface_font_system, systemFont.displayName),
                )
            )
        } else if (choice == InterfaceFont.SYSTEM) {
            add(DropDownOption(InterfaceFont.SYSTEM, stringResource(Res.string.settings_interface_font_system_missing)))
        }
    }

    SettingDropDown(
        title = stringResource(Res.string.settings_interface_font_title),
        subtitle = stringResource(Res.string.settings_interface_font_subtitle),
        dropDownOptions = options,
        currentOption = choice,
        width = FONT_CONTROL_WIDTH,
        onOptionSelected = { onPick(it.value) },
    )
}

/**
 * The code font's row: a button that opens the list of fonts, and a line when the chosen font isn't installed, as when
 * the settings came from another machine. [loadInstalled] runs off the UI thread, once, as it opens every font.
 */
@Composable
internal fun CodeFontRow(
    codeFont: String?,
    isInstalled: (String) -> Boolean,
    loadInstalled: () -> List<String>,
    onPick: (String?) -> Unit,
) {
    var showList by remember { mutableStateOf(false) }
    // A press on the button while the list is open first dismisses the list as an outside click; this keeps the
    // button's own click from opening it again right away
    var dismissedAt by remember { mutableStateOf(0L) }
    var installed by remember { mutableStateOf<List<String>?>(null) }
    val isMissing = remember(codeFont) { codeFont != null && !isInstalled(codeFont) }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { loadInstalled() }
    }

    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FieldTitles(
                title = stringResource(Res.string.settings_code_font_title),
                subtitle = stringResource(Res.string.settings_code_font_subtitle),
            )

            if (isMissing && codeFont != null) {
                Text(
                    text = stringResource(Res.string.settings_code_font_missing, codeFont),
                    color = MaterialTheme.colors.amberText,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Box {
            val label = codeFont ?: stringResource(Res.string.settings_code_font_bundled_label)
            val title = stringResource(Res.string.settings_code_font_title)

            Row(
                modifier = Modifier
                    .width(FONT_CONTROL_WIDTH)
                    .border(2.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.background)
                    .handMouseClickable {
                        if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                            showList = !showList
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        role = Role.DropdownList
                        contentDescription = title
                    }
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Icon(
                    painter = painterResource(Res.drawable.dropdown),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground,
                )
            }

            if (showList) {
                val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
                val margin = with(LocalDensity.current) { MENU_WINDOW_MARGIN.roundToPx() }
                val close = {
                    showList = false
                    dismissedAt = System.currentTimeMillis()
                }

                Popup(
                    properties = PopupProperties(focusable = true),
                    popupPositionProvider = remember(gap, margin) { BelowRightAlignedPositionProvider(gap, margin) },
                    onDismissRequest = close,
                ) {
                    CodeFontList(
                        current = codeFont,
                        installed = installed.orEmpty(),
                        onPick = {
                            close()
                            onPick(it)
                        },
                        onClose = close,
                    )
                }
            }
        }
    }
}

/** The list's rows for [text]: the bundled font when its name has it, then the installed fonts that have it. */
internal data class CodeFontRows(val bundled: CodeFontChoice?, val installed: List<CodeFontChoice>) {
    val all: List<CodeFontChoice> = listOfNotNull(bundled) + installed

    /** Where row [row] of [all] is in the list, which has a header over each group. */
    fun itemIndexOf(row: Int): Int = when {
        row < 0 -> -1
        bundled != null && row == 0 -> 1
        else -> (if (bundled != null) 2 else 0) + 1 + row - (if (bundled != null) 1 else 0)
    }
}

internal fun codeFontRows(installed: List<String>, text: String): CodeFontRows {
    val typed = text.trim()
    val bundled = if (bundledFontShows(BUNDLED_CODE_FONT, typed)) {
        val start = if (typed.isEmpty()) 0 else BUNDLED_CODE_FONT.indexOf(typed, ignoreCase = true)
        CodeFontChoice(BUNDLED_CODE_FONT, start, typed.length)
    } else {
        null
    }

    return CodeFontRows(bundled, codeFontChoices(installed, typed, BUNDLED_CODE_FONT))
}

/**
 * The fonts to pick from: a field that keeps the focus and filters them, the bundled font, then the installed
 * monospaced fonts, each name in its own font. A pick applies and closes the list; ↓, ↑ and Enter work as in the clone
 * dialog's list, and Esc closes it.
 */
@Composable
internal fun CodeFontList(
    current: String?,
    installed: List<String>,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var highlighted by remember { mutableStateOf(-1) }
    val rows = remember(installed, text) { codeFontRows(installed, text) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val pick = { choice: CodeFontChoice -> onPick(choice.name.takeIf { it != BUNDLED_CODE_FONT }) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(highlighted, rows) {
        if (highlighted >= 0) listState.keepInView(rows.itemIndexOf(highlighted))
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
                highlighted = if (it.isBlank()) -1 else 0
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
                                text = stringResource(Res.string.settings_code_font_filter_hint),
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
                text = stringResource(Res.string.settings_code_font_none),
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
            rows.bundled?.let { bundled ->
                item(key = "bundled-header") {
                    ChoiceGroupHeader(stringResource(Res.string.settings_code_font_bundled))
                }

                item(key = "bundled") {
                    PickRow(
                        name = bundled.name,
                        matchStart = bundled.matchStart,
                        matchLength = bundled.matchLength,
                        isHighlighted = highlighted == 0,
                        onPick = { pick(bundled) },
                        fontFamily = bundledMonoTypography(),
                        leading = { CheckSlot(isChecked = current == null) },
                    ) {
                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = stringResource(Res.string.settings_code_font_default_tag),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (rows.installed.isNotEmpty()) {
                item(key = "installed-header") {
                    ChoiceGroupHeader(stringResource(Res.string.settings_code_font_installed))
                }

                val first = if (rows.bundled != null) 1 else 0

                items(rows.installed, key = { "font:" + it.name }) { choice ->
                    val family = remember(choice.name) { installedFontFamily(choice.name) }

                    PickRow(
                        name = choice.name,
                        matchStart = choice.matchStart,
                        matchLength = choice.matchLength,
                        isHighlighted = highlighted == first + rows.installed.indexOf(choice),
                        onPick = { pick(choice) },
                        fontFamily = family,
                        leading = { CheckSlot(isChecked = choice.name.equals(current, ignoreCase = true)) },
                    )
                }
            }
        }
    }
}

/** A check before the chosen font's name, and its room before the others'. */
@Composable
private fun CheckSlot(isChecked: Boolean) {
    Box(modifier = Modifier.padding(end = 8.dp).size(MARK_SIZE)) {
        if (isChecked) {
            Icon(
                painterResource(Res.drawable.check),
                contentDescription = stringResource(Res.string.settings_code_font_chosen),
                tint = MaterialTheme.colors.primaryVariant,
                modifier = Modifier.size(MARK_SIZE),
            )
        }
    }
}

private enum class PreviewChange { CONTEXT, REMOVED, ADDED }

private enum class PreviewKind { KEYWORD, FUNCTION, STRING }

private class PreviewLine(val old: Int?, val new: Int?, val change: PreviewChange, val parts: List<Pair<PreviewKind?, String>>)

private const val PREVIEW_HEADER = "@@ -18,3 +18,3 @@ fun hits(commits: List<Commit>, term: String)"

/** A few lines of a diff, with `->`, `!=`, `&&` and `>=`, which a font with ligatures joins when they're on. */
private val PREVIEW_LINES = listOf(
    PreviewLine(
        18, 18, PreviewChange.CONTEXT,
        listOf(
            PreviewKind.KEYWORD to "val", null to " terms = term.", PreviewKind.FUNCTION to "split", null to "(",
            PreviewKind.STRING to "\" \"", null to ")",
        ),
    ),
    PreviewLine(
        19, null, PreviewChange.REMOVED,
        listOf(
            PreviewKind.KEYWORD to "val", null to " hits = commits.", PreviewKind.FUNCTION to "filter",
            null to " { it.message.", PreviewKind.FUNCTION to "contains", null to "(term) }",
        ),
    ),
    PreviewLine(
        null, 19, PreviewChange.ADDED,
        listOf(
            PreviewKind.KEYWORD to "val", null to " hits = commits.", PreviewKind.FUNCTION to "filter",
            null to " { c -> c.message != ", PreviewKind.STRING to "\"\"", null to " && c.",
            PreviewKind.FUNCTION to "matches", null to "(terms) }",
        ),
    ),
    PreviewLine(
        20, 20, PreviewChange.CONTEXT,
        listOf(
            PreviewKind.KEYWORD to "return if", null to " (hits.size >= limit) hits.", PreviewKind.FUNCTION to "take",
            null to "(limit) ", PreviewKind.KEYWORD to "else", null to " hits",
        ),
    ),
)

/** A diff in the code font, with the ligatures as Settings has them, coloured as the diff pane colours code. */
@Composable
internal fun CodeFontPreview() {
    val colors = MaterialTheme.colors
    val syntax = MaterialTheme.syntaxColors
    val description = stringResource(Res.string.settings_code_font_preview)

    Column(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, colors.onBackground.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .background(colors.background)
            .semantics { contentDescription = description },
    ) {
        PreviewText(
            text = AnnotatedString(gutter(null, null) + PREVIEW_HEADER),
            color = colors.onBackgroundSecondary,
            background = colors.onBackground.copy(alpha = HEADER_BACKGROUND),
        )

        for (line in PREVIEW_LINES) {
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.onBackgroundSecondary)) { append(gutter(line.old, line.new)) }

                for ((kind, part) in line.parts) {
                    val color = when (kind) {
                        PreviewKind.KEYWORD -> syntax.keyword
                        PreviewKind.FUNCTION -> syntax.function
                        PreviewKind.STRING -> syntax.string
                        null -> Color.Unspecified
                    }

                    withStyle(SpanStyle(color = color)) { append(part) }
                }
            }

            PreviewText(
                text = text,
                color = colors.onBackground,
                background = when (line.change) {
                    PreviewChange.CONTEXT -> Color.Transparent
                    PreviewChange.REMOVED -> colors.diffLineRemoved
                    PreviewChange.ADDED -> colors.diffLineAdded
                },
            )
        }
    }
}

@Composable
private fun PreviewText(text: AnnotatedString, color: Color, background: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2.inCodeFont(),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.fillMaxWidth().background(background).padding(vertical = 2.dp),
    )
}

/** The old and the new line number, as the diff pane puts them before a line. */
private fun gutter(old: Int?, new: Int?): String = "%4s %4s   ".format(old?.toString().orEmpty(), new?.toString().orEmpty())
