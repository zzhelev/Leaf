// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.autostash_notice_after_abort_merge
import dev.app.leaf.app.generated.resources.autostash_notice_after_abort_rebase
import dev.app.leaf.app.generated.resources.autostash_notice_after_commit
import dev.app.leaf.app.generated.resources.autostash_notice_after_merge
import dev.app.leaf.app.generated.resources.autostash_notice_after_rebase
import dev.app.leaf.app.generated.resources.autostash_notice_conflicts
import dev.app.leaf.app.generated.resources.autostash_notice_conflicts_end
import dev.app.leaf.app.generated.resources.autostash_notice_more_files
import dev.app.leaf.app.generated.resources.autostash_notice_refused
import dev.app.leaf.app.generated.resources.autostash_notice_set_aside_merge
import dev.app.leaf.app.generated.resources.autostash_notice_set_aside_rebase
import dev.app.leaf.app.generated.resources.autostash_notice_title
import dev.app.leaf.app.generated.resources.generic_button_ok
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The files the notice lists. Putting a stash back can leave conflicts in hundreds. */
private const val MAX_LISTED_FILES = 10

/** The stash entry's name, which git gives it, and which the text sets in the monospace font. */
private const val AUTOSTASH = "autostash"

/**
 * Says that git couldn't put back the changes that it set aside before a merge or a rebase, and where they are
 * (fork-only). The commit or abort that tried succeeded, so it isn't an error, and a toast would be gone too soon.
 */
@Composable
fun AutostashNoticeDialog(
    notice: AutostashNotice,
    onAccept: () -> Unit,
) {
    MaterialDialog(onCloseRequested = onAccept) {
        AutostashNoticeContent(notice, onAccept)
    }
}

/** [AutostashNoticeDialog]'s content, which a test renders without a window. */
@Composable
fun AutostashNoticeContent(
    notice: AutostashNotice,
    onAccept: () -> Unit,
) {
    Column(modifier = Modifier.width(580.dp)) {
        Row {
            Text(
                text = stringResource(Res.string.autostash_notice_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
            )

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                painterResource(Res.drawable.warning),
                contentDescription = null,
                tint = MaterialTheme.styleColors.warningIcon,
                modifier = Modifier.size(24.dp),
            )
        }

        SelectionContainer {
            Text(
                text = autostashNoticeText(notice),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        PrimaryButton(
            text = stringResource(Res.string.generic_button_ok),
            onClick = onAccept,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 32.dp),
        )
    }
}

/**
 * What git set aside and when it tried to put it back, then the files where that left conflicts, or else that it
 * didn't apply. The setting, the files and the stash's name are in the monospace font.
 */
@Composable
fun autostashNoticeText(notice: AutostashNotice): AnnotatedString {
    val (setAside, setting) = when (notice.setAsideFor) {
        AutostashOperation.MERGE -> stringResource(Res.string.autostash_notice_set_aside_merge) to "merge.autoStash"
        AutostashOperation.REBASE -> stringResource(Res.string.autostash_notice_set_aside_rebase) to "rebase.autoStash"
    }
    val after = stringResource(
        when (notice.putBackAfter) {
            AutostashPutBackAfter.OPERATION -> when (notice.setAsideFor) {
                AutostashOperation.MERGE -> Res.string.autostash_notice_after_merge
                AutostashOperation.REBASE -> Res.string.autostash_notice_after_rebase
            }

            AutostashPutBackAfter.COMMIT -> Res.string.autostash_notice_after_commit
            AutostashPutBackAfter.ABORT -> when (notice.setAsideFor) {
                AutostashOperation.MERGE -> Res.string.autostash_notice_after_abort_merge
                AutostashOperation.REBASE -> Res.string.autostash_notice_after_abort_rebase
            }
        }
    )
    val listed = notice.conflicts.take(MAX_LISTED_FILES)
    val moreFiles = notice.conflicts.size - listed.size
    val conflicts = stringResource(Res.string.autostash_notice_conflicts, after)
    val conflictsEnd = stringResource(Res.string.autostash_notice_conflicts_end)
    val refused = stringResource(Res.string.autostash_notice_refused, after)
    val more = pluralStringResource(Res.plurals.autostash_notice_more_files, moreFiles, moreFiles)
    val monospace = SpanStyle(fontFamily = monoTypography())

    return buildAnnotatedString {
        appendStyling(setAside, listOf(setting), monospace)

        if (notice.conflicts.isEmpty()) {
            append(" ")
            appendStyling(refused, listOf(AUTOSTASH), monospace)
        } else {
            append(" ")
            append(conflicts)
            append("\n\n")

            for (file in listed) {
                withStyle(monospace) { append(file) }
                append("\n")
            }

            if (moreFiles > 0) {
                append(more)
                append("\n")
            }

            append("\n")
            appendStyling(conflictsEnd, listOf(AUTOSTASH), monospace)
        }
    }
}

/** Appends [text] with [parts] of it in [style], wherever they are. */
private fun AnnotatedString.Builder.appendStyling(text: String, parts: List<String>, style: SpanStyle) {
    val start = length
    append(text)

    for (part in parts) {
        var index = text.indexOf(part)

        while (index >= 0) {
            addStyle(style, start + index, start + index + part.length)
            index = text.indexOf(part, index + part.length)
        }
    }
}
