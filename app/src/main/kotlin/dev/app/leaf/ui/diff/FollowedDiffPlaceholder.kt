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
import androidx.compose.material.IconButton
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.diff_followed_missing_hint
import dev.app.leaf.app.generated.resources.diff_followed_missing_title
import dev.app.leaf.app.generated.resources.diff_followed_unreadable_hint
import dev.app.leaf.app.generated.resources.diff_followed_unreadable_title
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.repositoryopen.MissingFollowedFile
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the diff area shows while the commit selected doesn't have the file that was open (fork-only): the file's path in
 * the diff's own header, and a line saying why there is no diff. The area stays, so the log above it doesn't jump while
 * the user steps through commits, and the next commit that changes the file shows its diff again.
 */
@Composable
fun FollowedDiffPlaceholder(
    missing: MissingFollowedFile,
    /** Esc, as the tab sends it for a diff: the placeholder closes like one. */
    closeRequests: Flow<Unit>,
    /** The placeholder is up: it joins the views that Esc closes. */
    onShown: () -> Unit,
    onClose: () -> Unit,
    logToggle: (@Composable () -> Unit)? = null,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnShown by rememberUpdatedState(onShown)

    LaunchedEffect(missing.path, closeRequests) {
        currentOnShown()
        closeRequests.collectLatest { currentOnClose() }
    }

    val path = missing.path
    val fileName = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', missingDelimiterValue = "")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colors.tertiarySurface)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f, true)) {
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, false),
                    )
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Text(
                    text = fileName,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                logToggle?.invoke()

                IconButton(onClick = onClose, modifier = Modifier.handOnHover()) {
                    Icon(
                        painter = painterResource(Res.drawable.close),
                        contentDescription = "Close diff",
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (missing.unreadable) {
                        stringResource(Res.string.diff_followed_unreadable_title, fileName, missing.commit.shortHash)
                    } else {
                        stringResource(Res.string.diff_followed_missing_title, fileName, missing.commit.shortHash)
                    },
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(
                        if (missing.unreadable) Res.string.diff_followed_unreadable_hint else Res.string.diff_followed_missing_hint
                    ),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
