// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.EditorError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IGetWorktreePathGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.OpenFileInExternalAppUseCase
import dev.app.leaf.domain.worktrees.isLinkedWorktreeGitDir
import dev.app.leaf.domain.worktrees.sharedGitDirOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * Opens a file of the tab's working tree in an editor (fork-only), as a task with no progress and no toast: a failure
 * shows in `ErrorDialog`.
 */
class OpenInEditorUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val settings: AppSettingsService,
    private val editors: IExternalEditors,
    private val launcher: IEditorLauncher,
    private val openFileInExternalApp: OpenFileInExternalAppUseCase,
    private val getWorktreePath: IGetWorktreePathGitAction,
) {
    /**
     * Opens [path], relative to the working tree, at [line] (from 1) when there is one: in the editor [editorId], or
     * else the repository's own editor, or else the default one.
     */
    operator fun invoke(path: String, line: Int?, editorId: String? = null): Job = useCaseExecutor.executeLaunch(
        taskType = TaskType.OpenInEditor,
        dataToRefresh = emptyArray(),
        isForegroundTask = false,
    ) { gitDir ->
        val workTree = getWorktreePath(gitDir).bind()
        val file = File(workTree, path)

        if (!file.exists()) return@executeLaunch Either.Err(EditorError.FileMissing(path))

        val editorSettings = settings.editorSettings.first()
        val id = editorId ?: editorSettings.editorFor(sharedGitDirOf(gitDir))
        val folder = folderToOpenWith(workTree, isLinkedWorktreeGitDir(gitDir), editorSettings.linkedWorktree)
        val target = EditorTarget(file.absolutePath, line, folder)

        fun opening(installed: List<InstalledEditor>) =
            editorOpening(editorChoice(id, installed, editorSettings.customCommand), target, workTree, currentOs == OS.MAC)

        // An editor installed since Leaf last looked is found when it's asked for
        val opening = opening(editors.installed()).let {
            if (it is EditorOpening.Refused && it.error is EditorError.NotInstalled) {
                opening(editors.installed(refresh = true))
            } else {
                it
            }
        }

        when (opening) {
            is EditorOpening.Run -> launcher.start(opening.command, workTree)
            is EditorOpening.WithDefaultApp -> {
                openFileInExternalApp(opening.file)
                Either.Ok(Unit)
            }

            is EditorOpening.Refused -> Either.Err(opening.error)
        }
    }
}

/** Shows a file of the tab's working tree in the system's file manager (fork-only). */
class ShowInFileManagerUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val launcher: IEditorLauncher,
    private val getWorktreePath: IGetWorktreePathGitAction,
) {
    /** Shows [path], relative to the working tree, or the nearest folder of it that exists, as for a deleted file. */
    operator fun invoke(path: String): Job = useCaseExecutor.executeLaunch(
        taskType = TaskType.ShowInFileManager,
        dataToRefresh = emptyArray(),
        isForegroundTask = false,
    ) { gitDir ->
        val workTree = File(getWorktreePath(gitDir).bind())
        val shown = generateSequence(File(workTree, path)) { it.parentFile }
            .takeWhile { it.startsWith(workTree) }
            .firstOrNull { it.exists() }
            ?: workTree

        launcher.reveal(shown.absolutePath)
    }
}
