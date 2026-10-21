package dev.app.leaf.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.roleBorder
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.tertiarySurface
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Preview
@Composable
fun SecondaryButtonPreview() {
    SecondaryButton(
        text = "Click me!",
        onClick = {},
    )
}

@Composable
fun SecondaryButton(
    modifier: Modifier = Modifier,
    text: String,
    // Fork-only: what the button does, which sets its colours in the style. The two below default to them
    colors: RoleColors = MaterialTheme.styleColors.action,
    textColor: Color = colors.content,
    backgroundButton: Color = colors.background,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundButton)
            // A fill of the caller's own gets no line of the role's
            .roleBorder(if (backgroundButton == colors.background) colors.border else Color.Transparent, shape)
            .handMouseClickable { onClick() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = textColor,
            maxLines = maxLines,
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
        )
    }
}

@Composable
fun SecondaryButtonCompactable(
    modifier: Modifier = Modifier,
    icon: DrawableResource,
    text: String,
    // Fork-only: what the button does, which sets its colours in the style. The two below default to them
    colors: RoleColors = MaterialTheme.styleColors.action,
    onBackgroundColor: Color = colors.content,
    backgroundButton: Color = colors.background,
    maxLines: Int = 1,
    isParentHovered: Boolean,
    onClick: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    var isExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(isHovered, isParentHovered) {
//        isExpanded = when {
//            isHovered -> true
//            isExpanded && isParentHovered -> true
//            else -> false
//        }
    }

    val targetBackground: Color
    val iconColor: Color
    val iconPadding: Float
    val border: Color

    if (isExpanded) {
        targetBackground = backgroundButton
        iconColor = onBackgroundColor
        iconPadding = 12f
        border = if (backgroundButton == colors.background) colors.border else Color.Transparent
    } else {
        targetBackground = MaterialTheme.colors.tertiarySurface
        iconColor = MaterialTheme.colors.onBackground
        iconPadding = 0f
        border = Color.Transparent
    }

    val shape = RoundedCornerShape(4.dp)

    val backgroundColorState by animateColorAsState(targetBackground)
    val iconColorState by animateColorAsState(iconColor)
    val iconPaddingState by animateFloatAsState(iconPadding)

    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColorState)
            .roleBorder(border, shape)
            .hoverable(hoverInteraction)
            .handMouseClickable { onClick() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = iconColorState,
            modifier = Modifier
                .padding(start = iconPaddingState.dp, end = 8.dp)
                .size(18.dp)
        )

        AnimatedVisibility(
            visible = isExpanded,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                color = onBackgroundColor,
                maxLines = maxLines,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, end = 12.dp)
            )
        }
    }
}