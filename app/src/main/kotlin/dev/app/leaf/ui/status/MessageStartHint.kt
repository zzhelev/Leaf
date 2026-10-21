// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch
import dev.app.leaf.app.generated.resources.commit_message_start_key
import dev.app.leaf.app.generated.resources.commit_message_start_key_and_template
import dev.app.leaf.app.generated.resources.commit_message_start_template
import dev.app.leaf.app.generated.resources.commit_message_start_template_tooltip
import dev.app.leaf.app.generated.resources.info
import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.commitmessage.MessageStart
import dev.app.leaf.domain.commitmessage.TicketKeyStyle
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * Fork-only: what an empty commit field offers to start from, the branch's ticket key and git's commit template
 * (StatusState.messageStart). The field shows it in place of its hint while it has the focus, and Tab writes it.
 */

/**
 * What an empty commit field can start from (fork-only): the ticket key in the branch's name ([key]), in the style of
 * the repository's subjects ([keyStyle]), and the file that `commit.template` names ([template]).
 */
data class MessageStartInputs(
    val key: String? = null,
    val keyStyle: TicketKeyStyle = TicketKeyStyle.COLON,
    val template: CommitTemplate? = null,
)

/** [start] in the field, with the caret where typing goes on. */
internal fun MessageStart.toFieldValue(): TextFieldValue = TextFieldValue(text, TextRange(caret))

/** Tab pressed alone, which writes the start that an empty field offers. */
internal fun isPlainTab(event: KeyEvent): Boolean = event.type == KeyEventType.KeyDown && event.key == Key.Tab &&
    !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed

/**
 * Says what Tab writes in the empty field: the key, from the branch's name, the commit template, or both. Its tooltip
 * names the template's file. Short enough for one line at the pane's default width.
 */
@Composable
internal fun MessageStartHint(start: MessageStart, modifier: Modifier = Modifier) {
    val key = start.key
    val text = when {
        key != null && start.template != null -> stringResource(Res.string.commit_message_start_key_and_template, key)
        key != null -> stringResource(Res.string.commit_message_start_key, key)
        else -> stringResource(Res.string.commit_message_start_template)
    }
    val tooltip = start.template?.let { stringResource(Res.string.commit_message_start_template_tooltip, it.path) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(if (key != null) Res.drawable.branch else Res.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )

        val label = @Composable {
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (tooltip != null) {
            DelayedTooltip(tooltip, Modifier.padding(start = 6.dp)) { label() }
        } else {
            Row(Modifier.padding(start = 6.dp)) { label() }
        }
    }
}
