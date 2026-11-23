// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.editor.CUSTOM_EDITOR
import dev.app.leaf.domain.editor.DEFAULT_APP_EDITOR
import dev.app.leaf.domain.editor.EditorSettings
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.editor.LinkedWorktreeOpening
import dev.app.leaf.domain.editor.knownEditor
import dev.app.leaf.domain.editor.repositoryNameOf
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.dropdowns.DropDownOption
import dev.app.leaf.viewmodels.SettingsAction
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Settings › Editor's values (fork-only): the settings, and the editors Leaf found, null while it looks. */
@Immutable
data class EditorSettingsState(
    val settings: EditorSettings = EditorSettings(),
    val installed: List<InstalledEditor>? = null,
)

private val EDITOR_DROP_DOWN_WIDTH = 240.dp

/** The choices of the Editor row: not chosen, the editors found, the default app and a command of the user's own. */
@Composable
internal fun editorOptions(state: EditorSettingsState): List<DropDownOption<String?>> {
    val chosen = state.settings.editor
    val installed = state.installed.orEmpty()
    val notFound = chosen?.takeIf { id ->
        id != DEFAULT_APP_EDITOR && id != CUSTOM_EDITOR && installed.none { it.id == id }
    }

    return buildList {
        add(DropDownOption(null, stringResource(Res.string.settings_editor_not_chosen)))
        installed.forEach { add(DropDownOption(it.id, it.name)) }

        // Chosen, and not on this computer (any more), or from a later version of Leaf
        if (notFound != null) {
            add(DropDownOption(notFound, stringResource(Res.string.settings_editor_not_found, knownEditor(notFound)?.name ?: notFound)))
        }

        add(DropDownOption(DEFAULT_APP_EDITOR, stringResource(Res.string.settings_editor_default_app)))
        add(DropDownOption(CUSTOM_EDITOR, stringResource(Res.string.settings_editor_custom)))
    }
}

@Composable
internal fun EditorSettingsPage(state: EditorSettingsState, onAction: (SettingsAction) -> Unit) {
    val settings = state.settings

    SettingDropDown(
        title = stringResource(Res.string.settings_editor_title),
        subtitle = stringResource(Res.string.settings_editor_subtitle),
        dropDownOptions = editorOptions(state),
        onOptionSelected = { onAction(SettingsAction.SetConfig(AppConfig.Editor(it.value))) },
        currentOption = settings.editor,
        width = EDITOR_DROP_DOWN_WIDTH,
    )

    if (settings.editor == CUSTOM_EDITOR) {
        SettingTextInput(
            title = stringResource(Res.string.settings_editor_command_title),
            subtitle = stringResource(Res.string.settings_editor_command_subtitle),
            value = settings.customCommand,
            onValueChanged = { onAction(SettingsAction.SetConfig(AppConfig.EditorCommand(it))) },
        )
    }

    SettingDropDown(
        title = stringResource(Res.string.settings_editor_linked_worktree_title),
        subtitle = stringResource(Res.string.settings_editor_linked_worktree_subtitle),
        dropDownOptions = listOf(
            DropDownOption(
                LinkedWorktreeOpening.WORKTREE,
                stringResource(Res.string.settings_editor_linked_worktree_open_worktree),
            ),
            DropDownOption(
                LinkedWorktreeOpening.FILE_ALONE,
                stringResource(Res.string.settings_editor_linked_worktree_open_file),
            ),
        ),
        onOptionSelected = { onAction(SettingsAction.SetConfig(AppConfig.EditorLinkedWorktree(it.value))) },
        currentOption = settings.linkedWorktree,
        width = EDITOR_DROP_DOWN_WIDTH,
    )

    RepositoryEditors(state, onAction)
}

/** The repositories that have an editor of their own, each with a button that takes it away. */
@Composable
private fun RepositoryEditors(state: EditorSettingsState, onAction: (SettingsAction) -> Unit) {
    val editors = state.settings.repositoryEditors

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        FieldTitles(
            title = stringResource(Res.string.settings_editor_repositories_title),
            subtitle = stringResource(Res.string.settings_editor_repositories_subtitle),
        )

        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        ) {
            if (editors.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_editor_repositories_none),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            editors.entries.sortedBy { repositoryNameOf(it.key).lowercase() }.forEachIndexed { index, (repository, id) ->
                if (index > 0) Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.12f))

                RepositoryEditorRow(
                    repository = repository,
                    editorName = editorNameOf(id, state),
                    onRemove = {
                        onAction(SettingsAction.SetConfig(AppConfig.RepositoryEditors(editors - repository)))
                    },
                )
            }
        }
    }
}

@Composable
private fun editorNameOf(id: String, state: EditorSettingsState): String = when (id) {
    DEFAULT_APP_EDITOR -> stringResource(Res.string.settings_editor_default_app)
    CUSTOM_EDITOR -> stringResource(Res.string.settings_editor_custom)
    else -> state.installed?.firstOrNull { it.id == id }?.name ?: knownEditor(id)?.name ?: id
}

@Composable
private fun RepositoryEditorRow(repository: String, editorName: String, onRemove: () -> Unit) {
    val name = repositoryNameOf(repository)
    val removeLabel = stringResource(Res.string.settings_editor_repository_remove, name)

    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
        )

        Text(
            text = editorName,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Icon(
            painter = painterResource(Res.drawable.close),
            contentDescription = removeLabel,
            tint = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier
                .size(26.dp)
                .handMouseClickable { onRemove() }
                .padding(5.dp),
        )
    }
}
