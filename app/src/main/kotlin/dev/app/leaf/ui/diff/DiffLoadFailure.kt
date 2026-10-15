// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.PartialCloneFetchError
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.stringResource

/**
 * Why a diff couldn't be loaded, in the pane where it would be (fork-only), with "Try again": a file that a partial
 * clone couldn't download, offline for example. The pane used to close without a word. It stays until the user picks
 * another file or the diff loads.
 */
@Composable
fun DiffLoadFailure(
    error: AppError,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (error) {
        is PartialCloneFetchError -> stringResource(Res.string.diff_failed_download_title, error.remote)
        else -> stringResource(Res.string.diff_failed_title)
    }

    val details = when (error) {
        is PartialCloneFetchError -> buildAnnotatedString {
            append(stringResource(Res.string.diff_failed_partial_clone))

            val cause = error.error?.getStyledErrorText()

            if (cause != null) {
                append("\n\n")
                append(cause)
            }
        }

        else -> error.getStyledErrorText()
    }

    Box(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .height(IntrinsicSize.Min)
                .background(MaterialTheme.colors.surface),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.error)
            )

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onBackground,
                )

                // git's output can be copied
                SelectionContainer {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                    )
                }

                SecondaryButton(
                    text = stringResource(Res.string.diff_failed_try_again),
                    onClick = onTryAgain,
                )
            }
        }
    }
}
