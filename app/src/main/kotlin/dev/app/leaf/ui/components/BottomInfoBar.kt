package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.AppConstants
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.bottom_info_bar_app_version
import dev.app.leaf.app.generated.resources.bottom_info_bar_update_available
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.updates.Update
import org.jetbrains.compose.resources.stringResource

@Composable
fun BottomInfoBar(
    newUpdate: Update?,
    onOpenUrlInBrowser: (String) -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent?.invoke()

        Spacer(Modifier.weight(1f, true))

        if (newUpdate != null) {
            SecondaryButton(
                text = stringResource(Res.string.bottom_info_bar_update_available, newUpdate.appVersion),
                onClick = { onOpenUrlInBrowser(newUpdate.downloadUrl) },
                backgroundButton = MaterialTheme.colors.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        Text(
            stringResource(Res.string.bottom_info_bar_app_version, AppConstants.APP_VERSION),
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            modifier = Modifier.handMouseClickable {
                onOpenUrlInBrowser("https://github.com/zzhelev/Leaf/releases")
            }
        )
    }
}
