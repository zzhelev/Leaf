// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.find.parseLineNumber
import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.frameColors
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.withText
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.getParts
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.text.NumberFormat

/** The strip's height at the default text size; a bigger text size adds a line. */
private val FIND_BAR_HEIGHT = 36.dp
private val FIND_FIELD_HEIGHT = 28.dp
private val FIND_FIELD_MIN_WIDTH = 120.dp
private val FIND_FIELD_MAX_WIDTH = 280.dp
private val LINE_FIELD_WIDTH = 140.dp
/** The counter's most, so that what is typed keeps room in the field. */
private val COUNTER_MAX_WIDTH = 84.dp
private val FIELD_SHAPE = RoundedCornerShape(6.dp)

/**
 * The find strip (fork-only, upstream issues 275 and 312, option A of the mockup): under the diff's or blame's header,
 * either find (the find field with its counter, the previous and next match, match case) or go to line (the line field,
 * which goes to a line of the [lineCount] that the view numbers), as its [FindState.mode] says, then ×. The two keys
 * switch it, and each field keeps what was typed in it (option A of the second question, 2026-11-13: the two had looked
 * the same, side by side in one strip).
 */
@Composable
fun FindBar(
    state: FindState,
    lineCount: Int,
    onGoToLine: (Int) -> Unit,
    /** Where the focus goes when the strip closes while it has it. */
    focusAfterClose: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val findFocus = remember { FocusRequester() }
    val lineFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(state.focusRequest) {
        val request = state.focusRequest ?: return@LaunchedEffect
        if (!state.takeFocusRequest(request)) return@LaunchedEffect

        state.selectFieldText(request.field)

        try {
            when (request.field) {
                FindField.FIND -> findFocus.requestFocus()
                FindField.LINE -> lineFocus.requestFocus()
            }
        } catch (_: IllegalStateException) {
            // The strip isn't laid out
        }
    }

    // A strip that closes with the focus gives it to the view, so that its keys go on working
    val currentHasFocus by rememberUpdatedState(hasFocus)
    DisposableEffect(Unit) {
        onDispose {
            if (currentHasFocus) {
                try {
                    focusAfterClose?.requestFocus()
                } catch (_: IllegalStateException) {
                    // The view is gone too
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(FIND_BAR_HEIGHT.withText())
            .background(MaterialTheme.colors.background)
            .bottomEdge(MaterialTheme.frameColors.separator)
            .onFocusChanged {
                hasFocus = it.hasFocus
                state.isBarFocused = it.hasFocus
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // × is measured first, at the end; the fields take what's left, up to their widths
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (state.mode) {
                FindField.FIND -> FindPart(state, findFocus)
                FindField.LINE -> LinePart(state, lineCount, lineFocus, onGoToLine)
            }
        }

        BarButton(
            icon = null,
            description = stringResource(Res.string.find_close),
            onClick = { state.close() },
        )
    }
}

@Composable
private fun RowScope.FindPart(state: FindState, focusRequester: FocusRequester) {
    val matches = state.result.matches

    FindField(
        value = state.query,
        onValueChange = { state.query = it },
        description = stringResource(Res.string.find_placeholder),
        showsPlaceholder = true,
        focusRequester = focusRequester,
        leadingIcon = true,
        trailing = if (state.query.text.isEmpty()) null else stringResource(
            Res.string.find_counter,
            if (matches.isEmpty()) "0" else formatCount(state.current + 1),
            formatCount(matches.size) + if (state.result.isCapped) "+" else "",
        ),
        isError = state.query.text.isNotEmpty() && matches.isEmpty(),
        onEnter = { shift -> state.step(if (shift) SearchStep.PREVIOUS else SearchStep.NEXT) },
        modifier = Modifier
            .weight(1f, fill = false)
            .widthIn(min = FIND_FIELD_MIN_WIDTH, max = FIND_FIELD_MAX_WIDTH),
    )

    BarButton(
        icon = Icons.Default.KeyboardArrowUp,
        description = stringResource(Res.string.find_previous, keyText(KeybindingOption.FIND_PREVIOUS)),
        onClick = { state.step(SearchStep.PREVIOUS) },
    )

    BarButton(
        icon = Icons.Default.KeyboardArrowDown,
        description = stringResource(Res.string.find_next, keyText(KeybindingOption.FIND_NEXT)),
        onClick = { state.step(SearchStep.NEXT) },
    )

    MatchCaseToggle(isOn = state.matchCase, onToggle = { state.matchCase = !state.matchCase })
}

@Composable
private fun LinePart(state: FindState, lineCount: Int, focusRequester: FocusRequester, onGoToLine: (Int) -> Unit) {
    Text(
        text = stringResource(Res.string.find_go_to_line),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
    )

    FindField(
        value = state.lineText,
        onValueChange = { value -> state.lineText = value.copy(text = value.text.filter { it in '0'..'9' }) },
        description = stringResource(Res.string.find_line_placeholder),
        showsPlaceholder = false,
        focusRequester = focusRequester,
        leadingIcon = false,
        trailing = stringResource(Res.string.find_line_count, formatCount(lineCount)),
        isError = false,
        onEnter = { parseLineNumber(state.lineText.text)?.let(onGoToLine) },
        modifier = Modifier.width(LINE_FIELD_WIDTH.withText()),
    )
}

@Composable
private fun FindField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    /** What the field is, for screen readers, and its placeholder while it's empty when [showsPlaceholder]. */
    description: String,
    showsPlaceholder: Boolean,
    focusRequester: FocusRequester,
    leadingIcon: Boolean,
    trailing: String?,
    isError: Boolean,
    onEnter: (shift: Boolean) -> Unit,
    modifier: Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val edge = when {
        isError -> MaterialTheme.colors.error
        isFocused -> MaterialTheme.colors.primaryVariant
        else -> MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f)
    }

    Row(
        modifier = modifier
            .height(FIND_FIELD_HEIGHT.withText())
            .clip(FIELD_SHAPE)
            .background(MaterialTheme.colors.background)
            .border(width = if (isFocused) 2.dp else 1.dp, color = edge, shape = FIELD_SHAPE)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon) {
            Icon(
                painterResource(Res.drawable.search),
                contentDescription = null,
                tint = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (showsPlaceholder && value.text.isEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colors.primaryVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = description }
                    .onFocusChanged { isFocused = it.isFocused }
                    .onPreviewKeyEvent { keyEvent ->
                        when {
                            keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT) -> {
                                onEnter(false)
                                true
                            }

                            keyEvent.matchesBinding(KeybindingOption.SEARCH_PREVIOUS_RESULT) -> {
                                onEnter(true)
                                true
                            }

                            else -> false
                        }
                    },
            )
        }

        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = COUNTER_MAX_WIDTH),
            )
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector?, description: String, onClick: () -> Unit) {
    DelayedTooltip(description) {
        Box(
            modifier = Modifier
                .size(26.dp.withText())
                .clip(RoundedCornerShape(4.dp))
                .handMouseClickable(onClick = onClick)
                .semantics { contentDescription = description; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    painterResource(Res.drawable.close),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackground,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MatchCaseToggle(isOn: Boolean, onToggle: () -> Unit) {
    val description = stringResource(Res.string.find_match_case)

    DelayedTooltip(description) {
        Box(
            modifier = Modifier
                .height(22.dp.withText())
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isOn) MaterialTheme.colors.onSurface.copy(alpha = 0.2f) else MaterialTheme.colors.background,
                )
                .handMouseClickable(onClick = onToggle)
                .semantics {
                    contentDescription = description
                    role = Role.Checkbox
                    selected = isOn
                }
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Aa",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground,
            )
        }
    }
}

private fun keyText(option: KeybindingOption): String =
    option.keyBinding?.let { getParts(it).joinToString("+") }.orEmpty()

private fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)
