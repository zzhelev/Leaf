// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.diff_only_whitespace_body
import dev.app.leaf.app.generated.resources.diff_only_whitespace_show
import dev.app.leaf.app.generated.resources.diff_only_whitespace_title
import dev.app.leaf.app.generated.resources.diff_view_active_ignoring
import dev.app.leaf.app.generated.resources.diff_view_active_ignoring_amount
import dev.app.leaf.app.generated.resources.diff_view_active_marking
import dev.app.leaf.app.generated.resources.diff_view_active_not_wrapping
import dev.app.leaf.app.generated.resources.diff_view_ignore_change
import dev.app.leaf.app.generated.resources.diff_view_ignore_change_hint
import dev.app.leaf.app.generated.resources.diff_view_ignore_whitespace
import dev.app.leaf.app.generated.resources.diff_view_ignore_whitespace_hint
import dev.app.leaf.app.generated.resources.diff_view_menu_tooltip
import dev.app.leaf.app.generated.resources.diff_view_menu_tooltip_active
import dev.app.leaf.app.generated.resources.diff_view_show_all_changes
import dev.app.leaf.app.generated.resources.diff_view_show_whitespace
import dev.app.leaf.app.generated.resources.diff_view_show_whitespace_hint
import dev.app.leaf.app.generated.resources.diff_view_whitespace_title
import dev.app.leaf.app.generated.resources.diff_view_wrap_lines
import dev.app.leaf.app.generated.resources.diff_view_wrap_lines_hint
import dev.app.leaf.app.generated.resources.diff_whitespace_strip
import dev.app.leaf.app.generated.resources.diff_whitespace_strip_amount
import dev.app.leaf.app.generated.resources.diff_whitespace_strip_stage_off
import dev.app.leaf.app.generated.resources.diff_whitespace_strip_unstage_off
import dev.app.leaf.app.generated.resources.diff_whitespace_strip_show
import dev.app.leaf.app.generated.resources.info
import dev.app.leaf.app.generated.resources.tune
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** A press on the button while the menu is open first dismisses it as an outside click: don't open it again at once. */
private const val REOPEN_GUARD_MS = 300

/** What the View menu is set to: how whitespace is compared, and its two switches. */
data class DiffViewOptions(
    val whitespace: DiffWhitespaceMode,
    val showWhitespace: Boolean,
    val wrapLines: Boolean,
)

/**
 * What a view of a diff without a header of its own, such as file history, shows and sets of the View menu: it has no
 * wrap switch, as its lines always wrap.
 */
data class WhitespaceViewOptions(
    val whitespace: DiffWhitespaceMode,
    val showWhitespace: Boolean,
    val onWhitespaceChanged: (DiffWhitespaceMode) -> Unit,
    val onShowWhitespaceChanged: (Boolean) -> Unit,
)

/**
 * Whether the diff of [diffType] can ignore whitespace: an added or a deleted file has no other version to compare
 * with.
 */
fun canIgnoreWhitespace(diffType: DiffType?): Boolean =
    diffType == null || (diffType.statusType != StatusType.ADDED && diffType.statusType != StatusType.REMOVED)

/**
 * The diff header's "View options" button and its menu (fork-only): how whitespace is compared (show all changes,
 * ignore different amounts of whitespace, ignore all whitespace), Show whitespace characters and Wrap long lines, in one 32 dp
 * button, as the header has little room on a laptop. The button is lit while a choice differs from how a diff first
 * looks, and its tooltip says which.
 *
 * [onWrapLinesChanged] is null where wrapping isn't a choice, such as file history, and the menu then leaves it out.
 */
@Composable
fun DiffViewMenu(
    diffType: DiffType?,
    options: DiffViewOptions,
    onWhitespaceChanged: (DiffWhitespaceMode) -> Unit,
    onShowWhitespaceChanged: (Boolean) -> Unit,
    onWrapLinesChanged: ((Boolean) -> Unit)?,
) {
    val labels = DiffViewMenuLabels(
        whitespaceTitle = stringResource(Res.string.diff_view_whitespace_title),
        showAllChanges = stringResource(Res.string.diff_view_show_all_changes),
        ignoreChange = stringResource(Res.string.diff_view_ignore_change),
        ignoreChangeHint = stringResource(Res.string.diff_view_ignore_change_hint),
        ignoreAll = stringResource(Res.string.diff_view_ignore_whitespace),
        ignoreAllHint = stringResource(Res.string.diff_view_ignore_whitespace_hint),
        show = stringResource(Res.string.diff_view_show_whitespace),
        showHint = stringResource(Res.string.diff_view_show_whitespace_hint),
        wrap = stringResource(Res.string.diff_view_wrap_lines),
        wrapHint = stringResource(Res.string.diff_view_wrap_lines_hint),
    )

    val items = diffViewMenuItems(
        showsIgnoreWhitespace = canIgnoreWhitespace(diffType),
        options = options,
        labels = labels,
        onWhitespaceChanged = onWhitespaceChanged,
        onShowWhitespaceChanged = onShowWhitespaceChanged,
        onWrapLinesChanged = onWrapLinesChanged,
    )

    val active = buildList {
        if (canIgnoreWhitespace(diffType)) {
            when (options.whitespace) {
                DiffWhitespaceMode.SHOW_ALL -> Unit
                DiffWhitespaceMode.IGNORE_CHANGE -> add(stringResource(Res.string.diff_view_active_ignoring_amount))
                DiffWhitespaceMode.IGNORE_ALL -> add(stringResource(Res.string.diff_view_active_ignoring))
            }
        }
        if (options.showWhitespace) add(stringResource(Res.string.diff_view_active_marking))
        if (onWrapLinesChanged != null && !options.wrapLines) add(stringResource(Res.string.diff_view_active_not_wrapping))
    }
    val tooltip = if (active.isEmpty()) {
        stringResource(Res.string.diff_view_menu_tooltip)
    } else {
        stringResource(Res.string.diff_view_menu_tooltip_active, active.joinToString(", "))
    }

    ViewMenuButton(items = items, isLit = active.isNotEmpty(), tooltip = tooltip)
}

internal data class DiffViewMenuLabels(
    val whitespaceTitle: String,
    val showAllChanges: String,
    val ignoreChange: String,
    val ignoreChangeHint: String,
    val ignoreAll: String,
    val ignoreAllHint: String,
    val show: String,
    val showHint: String,
    val wrap: String,
    val wrapHint: String,
)

/**
 * The menu's rows: the way whitespace is compared, one row for each [DiffWhitespaceMode] with the current one checked,
 * then the two switches. They stay open when one is chosen, so that a reviewer can set two in one go.
 */
internal fun diffViewMenuItems(
    showsIgnoreWhitespace: Boolean,
    options: DiffViewOptions,
    labels: DiffViewMenuLabels,
    onWhitespaceChanged: (DiffWhitespaceMode) -> Unit,
    onShowWhitespaceChanged: (Boolean) -> Unit,
    onWrapLinesChanged: ((Boolean) -> Unit)?,
): List<SortMenuItem> = buildList {
    if (showsIgnoreWhitespace) {
        add(SortMenuItem.Title(labels.whitespaceTitle))

        for (mode in DiffWhitespaceMode.entries) {
            add(
                SortMenuItem.Option(
                    label = when (mode) {
                        DiffWhitespaceMode.SHOW_ALL -> labels.showAllChanges
                        DiffWhitespaceMode.IGNORE_CHANGE -> labels.ignoreChange
                        DiffWhitespaceMode.IGNORE_ALL -> labels.ignoreAll
                    },
                    isChecked = options.whitespace == mode,
                    hint = when (mode) {
                        DiffWhitespaceMode.SHOW_ALL -> null
                        DiffWhitespaceMode.IGNORE_CHANGE -> labels.ignoreChangeHint
                        DiffWhitespaceMode.IGNORE_ALL -> labels.ignoreAllHint
                    },
                    // The mode that is on stays on
                    onClick = { if (options.whitespace != mode) onWhitespaceChanged(mode) },
                )
            )
        }

        add(SortMenuItem.Divider)
    }

    add(
        SortMenuItem.Option(
            label = labels.show,
            isChecked = options.showWhitespace,
            hint = labels.showHint,
            onClick = { onShowWhitespaceChanged(!options.showWhitespace) },
        )
    )

    if (onWrapLinesChanged != null) {
        add(
            SortMenuItem.Option(
                label = labels.wrap,
                isChecked = options.wrapLines,
                hint = labels.wrapHint,
                onClick = { onWrapLinesChanged(!options.wrapLines) },
            )
        )
    }
}

/** A 32 dp icon button as the header's other icons, lit like a toggled one while [isLit], with a menu of [items]. */
@Composable
private fun ViewMenuButton(items: List<SortMenuItem>, isLit: Boolean, tooltip: String) {
    var showMenu by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableStateOf(0L) }

    Box {
        DelayedTooltip(tooltip) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .run {
                        if (isLit) background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f)) else this
                    }
                    .handMouseClickable {
                        if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                            showMenu = !showMenu
                        }
                    }
                    .padding(4.dp)
            ) {
                Icon(
                    painterResource(Res.drawable.tune),
                    contentDescription = tooltip,
                    tint = MaterialTheme.colors.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        if (showMenu) {
            SortMenuPopup(
                items = items,
                onDismissRequest = {
                    showMenu = false
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}

/** What the strip says about the buttons of the hunks and lines of [diffType] that aren't there, or null for none. */
@Composable
fun whitespaceActionsOffText(diffType: DiffType): String? = when (lineActionsOf(diffType)) {
    emptyList<LineAction>() -> null
    listOf(LineAction.UNSTAGE) -> stringResource(Res.string.diff_whitespace_strip_unstage_off)
    else -> stringResource(Res.string.diff_whitespace_strip_stage_off)
}

/**
 * The line under the header while the diff ignores whitespace in [mode]: it says that lines are hidden, and where the
 * buttons of hunks and lines went ([actionsOff], from [whitespaceActionsOffText]), and "Show them" turns it off with
 * one click.
 */
@Composable
fun WhitespaceIgnoredStrip(mode: DiffWhitespaceMode, actionsOff: String?, onShowThem: () -> Unit) {
    val lineColor = MaterialTheme.colors.onBackground.copy(alpha = 0.15f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colors.secondarySurface)
            // A hairline under it, as the hunk header below has the same background
            .drawBehind {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height - 0.5f),
                    end = Offset(size.width, size.height - 0.5f),
                    strokeWidth = 1f,
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(Res.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(14.dp),
        )

        val ignoring = when (mode) {
            DiffWhitespaceMode.IGNORE_CHANGE -> stringResource(Res.string.diff_whitespace_strip_amount)
            else -> stringResource(Res.string.diff_whitespace_strip)
        }
        val message = ignoring + if (actionsOff != null) " $actionsOff" else ""

        Text(
            text = message,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Text(
            text = stringResource(Res.string.diff_whitespace_strip_show),
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.handMouseClickable { onShowThem() },
        )
    }
}

/** The body of the diff of a file that only changed whitespace, which the diff hides: it would be blank. */
@Composable
fun OnlyWhitespaceChanged(onShowChanges: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.diff_only_whitespace_title),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.diff_only_whitespace_body),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                textAlign = TextAlign.Center,
            )
            SecondaryButton(
                text = stringResource(Res.string.diff_only_whitespace_show),
                onClick = onShowChanges,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
