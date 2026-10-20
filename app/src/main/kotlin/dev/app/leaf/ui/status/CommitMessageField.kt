// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.status_header_more_actions
import dev.app.leaf.app.generated.resources.uncommited_changes_amend_menu_item
import dev.app.leaf.app.generated.resources.uncommited_changes_message_amending
import dev.app.leaf.app.generated.resources.uncommited_changes_message_read_only
import dev.app.leaf.app.generated.resources.uncommited_changes_message_title
import dev.app.leaf.app.generated.resources.uncommited_changes_message_title_merge
import dev.app.leaf.app.generated.resources.uncommited_changes_text_input_label_message
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.STATUS_COMMIT_TITLE_HEIGHT
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.HeaderMenuButton
import dev.app.leaf.ui.components.sort.SortMenuItem
import org.jetbrains.compose.resources.stringResource

/** How opaque the placeholder is: the reporter of upstream issue 344 asked for 30 to 50% transparency. */
internal const val COMMIT_MESSAGE_PLACEHOLDER_ALPHA = 0.6f

/**
 * What the commit message's title row says and offers (fork-only, upstream issue 344), by the repository's state.
 *
 * @property isMergeMessage the field shows git's message for a merge in progress
 * @property isReadOnly a rebase stopped at a commit that can't be amended: the field shows the last message and can't
 * be edited
 * @property amend the "Amend last commit" item of the row's menu, or null where there is nothing to amend: during a
 * merge, a cherry-pick, a revert, and at the rebase's other stops
 */
internal data class CommitMessageHeader(
    val isMergeMessage: Boolean,
    val isReadOnly: Boolean,
    val amend: AmendItem?,
)

/** The row's "Amend last commit" item, [isOn] when the next commit amends the last one. */
internal data class AmendItem(val isOn: Boolean)

internal fun commitMessageHeader(
    repositoryState: RepositoryState,
    isAmenableRebaseInteractive: Boolean,
    isAmend: Boolean,
    isAmendRebaseInteractive: Boolean,
) = CommitMessageHeader(
    isMergeMessage = repositoryState.isMerging,
    isReadOnly = repositoryState.isRebasing && !isAmenableRebaseInteractive,
    amend = when {
        // A rebase has its own switch, which only an interactive rebase's amendable stops use
        repositoryState.isRebasing -> AmendItem(isAmendRebaseInteractive).takeIf { isAmenableRebaseInteractive }
        repositoryState.isMerging || repositoryState.isCherryPicking || repositoryState.isReverting -> null
        else -> AmendItem(isAmend)
    },
)

/**
 * The title row above the commit message, laid out as the Staged and Unstaged headers are ([FilesChangedHeader]):
 * [STATUS_COMMIT_TITLE_HEIGHT] tall, on the same surface, with the same text. A "⋯" menu at its end has "Amend last
 * commit", and while that is on the row says so, as the button below reads Amend.
 */
@Composable
internal fun CommitMessageTitle(
    header: CommitMessageHeader,
    onToggleAmend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (header.isMergeMessage) {
        stringResource(Res.string.uncommited_changes_message_title_merge)
    } else {
        stringResource(Res.string.uncommited_changes_message_title)
    }
    val tag = when {
        header.isReadOnly -> stringResource(Res.string.uncommited_changes_message_read_only)
        header.amend?.isOn == true -> stringResource(Res.string.uncommited_changes_message_amending)
        else -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_COMMIT_TITLE_HEIGHT.dp)
            .background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                text = title,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Left,
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (tag != null) {
                Text(
                    // The title keeps its room, and the tag gets what is left
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    text = "· $tag",
                    // The accent that the Amend checkbox had when it was ticked
                    color = if (header.isReadOnly) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.secondary,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        header.amend?.let { amend ->
            HeaderMenuButton(
                items = amendMenuItems(amend, stringResource(Res.string.uncommited_changes_amend_menu_item), onToggleAmend),
                tooltip = stringResource(Res.string.status_header_more_actions),
            )
        }
    }
}

/** The row's menu: "Amend last commit", checked while it is on. It closes the menu, as there is nothing else to pick. */
internal fun amendMenuItems(amend: AmendItem, label: String, onToggleAmend: () -> Unit): List<SortMenuItem> = listOf(
    SortMenuItem.Option(
        label = label,
        isChecked = amend.isOn,
        closesMenu = true,
        onClick = onToggleAmend,
    ),
)

/**
 * The commit message's field: no label, but a hint while it is empty, as dim as the reporter of upstream issue 344 asked.
 * It has the lists' background and no underline until it has the focus. A field that can't be edited has no hint.
 */
@Composable
internal fun CommitMessageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    TextField(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = if (enabled) {
            {
                Text(
                    text = stringResource(Res.string.uncommited_changes_text_input_label_message),
                    style = MaterialTheme.typography.body1,
                    // The typography has a color of its own, which the field's placeholder color wouldn't override
                    color = MaterialTheme.colors.onBackground.copy(alpha = COMMIT_MESSAGE_PLACEHOLDER_ALPHA),
                )
            }
        } else {
            null
        },
        colors = commitMessageFieldColors(),
        textStyle = MaterialTheme.typography.body1,
    )
}

@Composable
private fun commitMessageFieldColors(): TextFieldColors = TextFieldDefaults.textFieldColors(
    textColor = MaterialTheme.colors.onBackground,
    disabledTextColor = MaterialTheme.colors.onBackgroundSecondary,
    backgroundColor = MaterialTheme.colors.background,
    cursorColor = MaterialTheme.colors.primaryVariant,
    focusedIndicatorColor = MaterialTheme.colors.primaryVariant,
    // Without them the field looks like text inside an input box, where the lists above are plain
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
