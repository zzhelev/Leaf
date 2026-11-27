// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.EditorSettings
import dev.app.leaf.domain.editor.IExternalEditors
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.editor.editorChoice
import dev.app.leaf.domain.editor.repositoryNameOf
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.worktrees.sharedGitDirOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The editor picker of "Open in another editor…" (fork-only): the editors to choose from for [path] (relative to the
 * working tree) at [line]. [defaultEditor] is the one of Settings › Editor, which the list marks, and the repository's
 * own is [repositoryEditor]. [isAtMenu] puts it where the file's menu was, and else in the window's middle.
 */
data class EditorPickerState(
    val path: String,
    val line: Int?,
    val editors: List<InstalledEditor>,
    val defaultEditor: String?,
    val repositoryEditor: String?,
    val repositoryName: String?,
    val isAtMenu: Boolean,
)

/** The tab's repository, once worked out: the common git dir by which its own editor is kept, or none. */
private data class TabRepository(val sharedGitDir: String?)

/**
 * Opening a tab's files in an editor and showing them in the file manager (fork-only): which editor "Open in …" names
 * for the tab's repository, and the picker that chooses another.
 */
class FileOpeningController(
    private val scope: CoroutineScope,
    gitDir: Flow<String?>,
    private val editorSettings: Flow<EditorSettings>,
    private val editors: IExternalEditors,
    private val openInEditor: (path: String, line: Int?, editorId: String?) -> Unit,
    private val showInFileManager: (path: String) -> Unit,
    private val saveSetting: suspend (AppConfig) -> Unit,
) {
    private val installed = MutableStateFlow<List<InstalledEditor>?>(null)

    /**
     * The tab's repository, null until it's worked out, on the IO threads. Until then, its own editor isn't known: the
     * default isn't taken in its place, which "Open in …" would name for a moment, and Remember would save as the
     * default.
     */
    private val repository: StateFlow<TabRepository?> = gitDir
        .distinctUntilChanged()
        .map { TabRepository(it?.let(::sharedGitDirOf)) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private suspend fun sharedGitDir(): String? = repository.filterNotNull().first().sharedGitDir

    init {
        scope.launch { installed.value = editors.installed() }
    }

    /** [choice], or null until the tab's repository is worked out. */
    private val settledChoice: StateFlow<EditorChoice?> =
        combine(editorSettings, repository.filterNotNull(), installed) { settings, repository, found ->
            editorChoice(settings.editorFor(repository.sharedGitDir), found.orEmpty(), settings.customCommand)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * What "Open in …" opens the tab's files with, none until the tab's repository is worked out. Until the editors
     * were looked for, a chosen editor counts as not installed, which only its name is taken from: opening looks for it.
     */
    val choice: StateFlow<EditorChoice> = settledChoice
        .map { it ?: EditorChoice.None }
        .stateIn(scope, SharingStarted.Eagerly, EditorChoice.None)

    private val _picker = MutableStateFlow<EditorPickerState?>(null)
    val picker: StateFlow<EditorPickerState?> = _picker

    /** Opens [path] at [line] in the repository's editor, or asks which editor when none was chosen yet. */
    fun open(path: String, line: Int?, isAtMenu: Boolean) {
        val settled = settledChoice.value

        if (settled == null) {
            scope.launch { openWith(settledChoice.filterNotNull().first(), path, line, isAtMenu) }
        } else {
            openWith(settled, path, line, isAtMenu)
        }
    }

    private fun openWith(choice: EditorChoice, path: String, line: Int?, isAtMenu: Boolean) {
        if (choice == EditorChoice.None) {
            chooseEditor(path, line, isAtMenu)
        } else {
            openInEditor(path, line, null)
        }
    }

    /** Shows the picker for [path] at [line], after looking for the editors again. */
    fun chooseEditor(path: String, line: Int?, isAtMenu: Boolean) {
        scope.launch {
            val found = editors.installed(refresh = true)
            installed.value = found
            val settings = editorSettings.first()
            val repository = sharedGitDir()

            _picker.value = EditorPickerState(
                path = path,
                line = line,
                editors = found,
                defaultEditor = settings.editor,
                repositoryEditor = repository?.let { settings.repositoryEditors[it] },
                repositoryName = repository?.let(::repositoryNameOf),
                isAtMenu = isAtMenu,
            )
        }
    }

    /**
     * Opens the picker's file in [editorId]. With [remember], the editor becomes the default when there is none yet,
     * and else the repository's own (no longer one of its own when it's the default).
     */
    fun pick(editorId: String, remember: Boolean) {
        val request = _picker.value ?: return
        _picker.value = null

        scope.launch {
            if (remember) {
                val settings = editorSettings.first()
                val repository = sharedGitDir()

                when {
                    settings.editor == null || repository == null -> saveSetting(AppConfig.Editor(editorId))
                    editorId == settings.editor ->
                        saveSetting(AppConfig.RepositoryEditors(settings.repositoryEditors - repository))

                    else -> saveSetting(AppConfig.RepositoryEditors(settings.repositoryEditors + (repository to editorId)))
                }
            }

            openInEditor(request.path, request.line, editorId)
        }
    }

    fun closePicker() {
        _picker.value = null
    }

    fun show(path: String) = showInFileManager(path)
}
