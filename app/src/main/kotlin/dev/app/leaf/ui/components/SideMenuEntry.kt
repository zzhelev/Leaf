package dev.app.leaf.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.chevron_right
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.painterResource

@Composable
fun SideMenuHeader(
    text: String,
    itemsCount: Int,
    isExpanded: Boolean,
    onExpand: () -> Unit = {},
    hoverIcon: @Composable (() -> Unit)? = null,
    sortAction: @Composable (() -> Unit)? = null,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .height(MaterialTheme.linesHeight.sidePanelItemHeight)
            .fillMaxWidth()
            .hoverable(hoverInteraction)
            .handMouseClickable { onExpand() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fork-only: mockup A of the window chrome review, a small label in capitals with its count, and no icon
        // (docs/fork/window-chrome.md, section 4)
        Icon(
            painter = painterResource(if (isExpanded) Res.drawable.expand_more else Res.drawable.chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier
                .padding(start = 8.dp, end = 4.dp)
                .size(14.dp),
        )

        val title = @Composable { modifier: Modifier ->
            Text(
                text = text.uppercase(),
                modifier = modifier,
                maxLines = 1,
                style = MaterialTheme.typography.body2,
                fontSize = SECTION_LABEL_SIZE,
                letterSpacing = 0.06.em,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackgroundSecondary,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (sortAction != null) {
            TitleWithTrailingAction(
                title = { title(Modifier) },
                action = { sortAction() },
                modifier = Modifier
                    .padding(start = 6.dp, end = 4.dp)
                    .weight(1f),
            )
        } else {
            title(
                Modifier
                    .padding(start = 6.dp, end = 8.dp)
                    .weight(1f)
            )
        }

        if (hoverIcon != null && isHovered) {
            hoverIcon()
        } else
            Text(
                text = itemsCount.toString(),
                style = MaterialTheme.typography.body2,
                fontSize = SECTION_LABEL_SIZE,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(end = 16.dp),
            )
    }
}

/** The section labels' and counts' size in the side panel (fork-only). */
private val SECTION_LABEL_SIZE = 11.sp
