package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.search
import dev.app.leaf.app.generated.resources.status_header_more_actions
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.headerBackground
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Immutable
data class ActionInfo(
    val applyToOneTitle: String,
    val applyToAllTitle: String,
    val applyToSelectedTitle: String,
    val icon: DrawableResource,
    /** Fork-only: the style's colours for what the action does. */
    val colors: RoleColors,
)

@Composable
fun FilesChangedHeader(
    title: String,
    showSearch: Boolean,
    showActionForSelected: Boolean,
    actionInfo: ActionInfo? = null,
    onAllAction: (() -> Unit)? = null,
    /** Fork-only: false greys the all-files button out and makes it do nothing. */
    allActionEnabled: Boolean = true,
    /** Shown before the search button, for example a sort and view menu. The title keeps priority over it. */
    sortAction: (@Composable () -> Unit)? = null,
    /** Fork-only: the items of a "⋯" menu between the search button and the all-files button, none for no menu. */
    moreItems: List<SortMenuItem> = emptyList(),
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    searchFilter: TextFieldValue,
    onSearchFilterChanged: (TextFieldValue) -> Unit,
    /**
     * Fork-only: the list folds to this header, with a chevron at its start. While folded, the header has no sort menu or
     * search, which act on the rows.
     */
    fold: SectionFold? = null,
    /** Fork-only: how many files the list has, after the title. */
    count: Int? = null,
) {
    val isFolded = fold?.isFolded == true
    val searchFocusRequester = remember { FocusRequester() }

    /**
     * State used to prevent the text field from getting the focus when returning from another tab
     */
    var requestFocus by remember { mutableStateOf(false) }

    val headerHoverInteraction = remember { MutableInteractionSource() }
    val isHeaderHovered by headerHoverInteraction.collectIsHoveredAsState()
    Column {
        Row(
            modifier = Modifier
                .height(34.dp)
                .fillMaxWidth()
                .headerBackground()
                .hoverable(headerHoverInteraction)
                .foldsSection(fold),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fold != null) {
                FoldChevron(fold.isFolded)
            }

            @Composable
            fun Title(modifier: Modifier) {
                Row(
                    modifier = modifier.padding(start = if (fold != null) 0.dp else 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f, fill = false),
                        text = title,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Left,
                        color = MaterialTheme.colors.onBackground,
                        style = MaterialTheme.typography.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (count != null) {
                        CountPill(count, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (sortAction != null && !isFolded) {
                TitleWithTrailingAction(
                    title = { Title(Modifier) },
                    action = sortAction,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Title(Modifier.weight(1f))
            }

            if (!isFolded) {
                IconButton(
                    onClick = {
                        onSearchFilterToggled(!showSearch)

                        if (!showSearch)
                            requestFocus = true
                    },
                    modifier = Modifier.handOnHover()
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.search),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colors.onBackground,
                    )
                }
            }

            if (moreItems.isNotEmpty()) {
                HeaderMenuButton(moreItems, stringResource(Res.string.status_header_more_actions))
            }

            if (actionInfo != null && onAllAction != null) {
                SecondaryButtonCompactable(
                    text = if (showActionForSelected) actionInfo.applyToSelectedTitle else actionInfo.applyToAllTitle,
                    icon = actionInfo.icon,
                    isParentHovered = isHeaderHovered,
                    colors = actionInfo.colors,
                    onClick = { if (allActionEnabled) onAllAction() },
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp).alpha(if (allActionEnabled) 1f else 0.5f),
                )
            }
        }

        if (showSearch && !isFolded) {
            SearchTextField(
                searchFilter = searchFilter,
                onSearchFilterChanged = onSearchFilterChanged,
                searchFocusRequester = searchFocusRequester,
                onSearchFocused = onSearchFocused,
                onClose = { onSearchFilterToggled(false) },
            )
        }

        LaunchedEffect(showSearch, requestFocus) {
            if (showSearch && requestFocus) {
                searchFocusRequester.requestFocus()
                requestFocus = false
            }
        }
    }
}