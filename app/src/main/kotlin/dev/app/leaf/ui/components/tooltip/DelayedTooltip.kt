package dev.app.leaf.ui.components.tooltip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.onBackgroundSecondary

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DelayedTooltip(text: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Fork-only: none beneath a dialog or a menu, nor after a click until the pointer leaves (TooltipLayers.kt)
    val visibility = rememberTooltipVisibility()

    TooltipArea(
        modifier = modifier.tooltipPresses(visibility),
        tooltip = {
            if (text != null && visibility.canShow) {
                val border = if (MaterialTheme.colors.isDark) {
                    BorderStroke(2.dp, MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.2f))
                } else
                    null

                Card(
                    backgroundColor = MaterialTheme.colors.background,
                    border = border,
                    elevation = 10.dp,
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        },
    ) {
        content()
    }
}