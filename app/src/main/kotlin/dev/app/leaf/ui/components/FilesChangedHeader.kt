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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.search
import dev.app.leaf.theme.tertiarySurface
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Immutable
data class ActionInfo(
    val applyToOneTitle: String,
    val applyToAllTitle: String,
    val applyToSelectedTitle: String,
    val icon: DrawableResource,
    val color: Color,
    val textColor: Color,
)

@Composable
fun FilesChangedHeader(
    title: String,
    showSearch: Boolean,
    showActionForSelected: Boolean,
    actionInfo: ActionInfo? = null,
    onAllAction: (() -> Unit)? = null,
    /** Shown before the search button, for example a sort and view menu. The title keeps priority over it. */
    sortAction: (@Composable () -> Unit)? = null,
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    searchFilter: TextFieldValue,
    onSearchFilterChanged: (TextFieldValue) -> Unit,
) {
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
                .background(color = MaterialTheme.colors.tertiarySurface)
                .hoverable(headerHoverInteraction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun Title(modifier: Modifier) {
                Text(
                    modifier = modifier.padding(start = 16.dp, end = 8.dp),
                    text = title,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Left,
                    color = MaterialTheme.colors.onBackground,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (sortAction != null) {
                TitleWithTrailingAction(
                    title = { Title(Modifier) },
                    action = sortAction,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Title(Modifier.weight(1f))
            }

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

            if (actionInfo != null && onAllAction != null) {
                SecondaryButtonCompactable(
                    text = if (showActionForSelected) actionInfo.applyToSelectedTitle else actionInfo.applyToAllTitle,
                    icon = actionInfo.icon,
                    isParentHovered = isHeaderHovered,
                    backgroundButton = actionInfo.color,
                    onBackgroundColor = actionInfo.textColor,
                    onClick = onAllAction,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                )
            }
        }

        if (showSearch) {
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