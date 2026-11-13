// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.log_search_close
import dev.app.leaf.app.generated.resources.log_search_next
import dev.app.leaf.app.generated.resources.log_search_placeholder
import dev.app.leaf.app.generated.resources.log_search_previous
import dev.app.leaf.app.generated.resources.log_search_tooltip
import dev.app.leaf.app.generated.resources.search
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.repositoryopen.LogSearch
import dev.app.leaf.theme.headerBackground
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.withText
import dev.app.leaf.ui.KeybindingHint
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The bar's height at the default text size, in dp; a bigger text size adds a line. */
private val LOG_FILTER_BAR_HEIGHT = 36.dp

/** The search field's and the branch filter's height, in dp. */
internal val LOG_FILTER_CONTROL_HEIGHT = 28.dp

/** The search field is no wider than this, at the bar's start, after the branch filter. */
private val LOG_SEARCH_MAX_WIDTH = 320.dp

/**
 * The log's filter bar (fork-only, option A of the log header's options, 2026-11-06), above the column headers, so that
 * the controls no longer read as headers of the Message column: the branch filter, the search field, and at the bar's
 * end the columns button. The search is a field in the bar, so searching adds no row.
 */
@Composable
internal fun LogFilterBar(
    branchFilterControl: @Composable () -> Unit,
    searchField: @Composable (Modifier) -> Unit,
    columnsMenuItems: List<SortMenuItem>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LOG_FILTER_BAR_HEIGHT.withText())
            .headerBackground()
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        branchFilterControl()

        // The field takes what the branch filter leaves, up to its width, at the start
        Box(modifier = Modifier.weight(1f)) {
            searchField(Modifier.widthIn(max = LOG_SEARCH_MAX_WIDTH).fillMaxWidth())
        }

        LogColumnsMenuButton(columnsMenuItems)
    }
}

/**
 * The log's search field (fork-only), always in the filter bar. Typing searches, Enter and Shift+Enter step through the
 * results, and the counter, the arrows and × show while it has text. Esc (through the tab) and × end the search and give
 * the focus to the log's list; leaving the field empty ends it too.
 */
@Composable
internal fun LogSearchField(
    search: LogSearch,
    /** The text the search had, when the field comes back while searching, such as after a diff covered the log. */
    initialText: String,
    onTextChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    /** The field has the focus: Esc ends the search first. */
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the focus goes when the search ends while the field has it. */
    focusAfterClose: FocusRequester? = null,
    /** The tab's Ctrl+F (⌘F), which puts the focus in the field. */
    focusRequests: Flow<Unit> = emptyFlow(),
) {
    val isSearching = search is LogSearch.SearchResults
    var text by remember { mutableStateOf(TextFieldValue(if (isSearching) initialText else "")) }
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val placeholder = stringResource(Res.string.log_search_placeholder)

    LaunchedEffect(focusRequests) {
        focusRequests.collect {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // The field isn't laid out
            }
        }
    }

    // An ended search empties the field, and the arrows go back to the log
    LaunchedEffect(isSearching) {
        if (!isSearching) {
            text = TextFieldValue("")

            if (isFocused) {
                try {
                    focusAfterClose?.requestFocus()
                } catch (_: IllegalStateException) {
                    // The log isn't shown
                }
            }
        }
    }

    DelayedTooltip(stringResource(Res.string.log_search_tooltip), modifier = modifier) {
        Row(
            modifier = Modifier
                .height(LOG_FILTER_CONTROL_HEIGHT.withText())
                .clip(FIELD_SHAPE)
                .background(MaterialTheme.colors.background)
                .border(
                    width = 1.dp,
                    color = if (isFocused) {
                        MaterialTheme.colors.primaryVariant
                    } else {
                        MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f)
                    },
                    shape = FIELD_SHAPE,
                )
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(Res.drawable.search),
                contentDescription = null,
                tint = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(14.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                BasicTextField(
                    value = text,
                    onValueChange = { value ->
                        val changed = value.text != text.text

                        text = value

                        if (changed) {
                            onTextChange(value.text)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colors.primaryVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = placeholder }
                        .onFocusChanged { state ->
                            val wasFocused = isFocused

                            isFocused = state.isFocused

                            if (state.isFocused && !wasFocused) {
                                // Ctrl+F (⌘F) on a field with a search selects it, to type another
                                text = text.copy(selection = TextRange(0, text.text.length))
                                onFocused()
                            } else if (!state.isFocused && wasFocused && text.text.isEmpty()) {
                                onClose()
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            when {
                                keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT) -> {
                                    onNext()
                                    true
                                }

                                keyEvent.matchesBinding(KeybindingOption.SEARCH_PREVIOUS_RESULT) -> {
                                    onPrevious()
                                    true
                                }

                                else -> false
                            }
                        },
                )
            }

            if (text.text.isNotEmpty() && search is LogSearch.SearchResults) {
                Text(
                    text = "${search.index}/${search.totalText}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                FieldButton(Icons.Default.KeyboardArrowUp, stringResource(Res.string.log_search_previous), onPrevious)
                FieldButton(Icons.Default.KeyboardArrowDown, stringResource(Res.string.log_search_next), onNext)
                FieldButton(Res.drawable.close, stringResource(Res.string.log_search_close), onClose)
            } else if (!isFocused) {
                KeybindingOption.FIND.keyBinding?.let { keybinding ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                        KeybindingHint(keybinding)
                    }
                }
            }
        }
    }
}

private val FIELD_SHAPE = RoundedCornerShape(6.dp)

@Composable
private fun FieldButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    FieldButton(description, onClick) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colors.onBackground, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FieldButton(icon: DrawableResource, description: String, onClick: () -> Unit) {
    FieldButton(description, onClick) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = MaterialTheme.colors.onBackground,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun FieldButton(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    DelayedTooltip(description) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .handMouseClickable(onClick = onClick)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}
