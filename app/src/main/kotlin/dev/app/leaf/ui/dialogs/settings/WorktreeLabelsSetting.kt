// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.settings_worktree_labels_add
import dev.app.leaf.app.generated.resources.settings_worktree_labels_built_in
import dev.app.leaf.app.generated.resources.settings_worktree_labels_label
import dev.app.leaf.app.generated.resources.settings_worktree_labels_pattern
import dev.app.leaf.app.generated.resources.settings_worktree_labels_remove
import dev.app.leaf.app.generated.resources.settings_worktree_labels_subtitle
import dev.app.leaf.app.generated.resources.settings_worktree_labels_title
import dev.app.leaf.domain.models.WorktreeLabelRule
import dev.app.leaf.domain.worktrees.BUILT_IN_WORKTREE_RULES
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.outlinedTextFieldColors
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.SecondaryButton
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Width of a rule's label, in the fields and the built-in rows. */
private val LABEL_WIDTH = 120.dp

/**
 * Settings → Worktrees → "Worktree labels" (fork-only): the user's rules, each a label and a folder pattern with a
 * button that removes it, then "Add label", then the built-in rules, greyed out, so the list shows everything that's
 * matched. Each change saves the whole list through [onRulesChanged], rules being typed included.
 */
@Composable
fun WorktreeLabelsSetting(
    rules: List<WorktreeLabelRule>,
    onRulesChanged: (List<WorktreeLabelRule>) -> Unit,
) {
    // The fields show what's typed. The setting comes back from the settings file later, so it only replaces them
    // when it changed elsewhere, or once it's read
    var shown by remember { mutableStateOf(rules) }
    var saved by remember { mutableStateOf<List<WorktreeLabelRule>?>(null) }

    LaunchedEffect(rules) {
        if (rules != saved) {
            shown = rules
        }
    }

    fun change(newRules: List<WorktreeLabelRule>) {
        shown = newRules
        saved = newRules
        onRulesChanged(newRules)
    }

    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldTitles(
            title = stringResource(Res.string.settings_worktree_labels_title),
            subtitle = stringResource(Res.string.settings_worktree_labels_subtitle),
        )

        shown.forEachIndexed { index, rule ->
            RuleFields(
                rule = rule,
                onRuleChanged = { changed -> change(shown.toMutableList().apply { set(index, changed) }) },
                onRemove = { change(shown.toMutableList().apply { removeAt(index) }) },
            )
        }

        SecondaryButton(
            text = stringResource(Res.string.settings_worktree_labels_add),
            onClick = { change(shown + WorktreeLabelRule(label = "", pattern = "")) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_worktree_labels_built_in),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )

            BUILT_IN_WORKTREE_RULES.forEach { rule ->
                BuiltInRule(rule.tool.label, rule.pattern)
            }
        }
    }
}

@Composable
private fun RuleFields(
    rule: WorktreeLabelRule,
    onRuleChanged: (WorktreeLabelRule) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdjustableOutlinedTextField(
            value = rule.label,
            hint = stringResource(Res.string.settings_worktree_labels_label),
            onValueChange = { onRuleChanged(rule.copy(label = it)) },
            modifier = Modifier.width(LABEL_WIDTH),
            colors = outlinedTextFieldColors(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.width(8.dp))

        AdjustableOutlinedTextField(
            value = rule.pattern,
            hint = stringResource(Res.string.settings_worktree_labels_pattern),
            onValueChange = { onRuleChanged(rule.copy(pattern = it)) },
            modifier = Modifier.weight(1f),
            colors = outlinedTextFieldColors(),
            singleLine = true,
        )

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(24.dp)
                .handOnHover(),
        ) {
            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = stringResource(Res.string.settings_worktree_labels_remove),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

/** A built-in rule, greyed out: it can't be changed, but a rule of the user's comes first. */
@Composable
private fun BuiltInRule(label: String, pattern: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH).padding(start = 8.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = pattern,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
