// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EditorError

/** The editors on this computer (fork-only). */
interface IExternalEditors {
    /** The editors that Leaf finds, in the order of [KNOWN_EDITORS]: looked for once, and again with [refresh]. */
    suspend fun installed(refresh: Boolean = false): List<InstalledEditor>
}

/** Starts editors and the file manager (fork-only). */
interface IEditorLauncher {
    /**
     * Runs [command] in [workingDirectory] and lets it go on: an error only when it can't start, or when it ends at
     * once with an exit code other than 0, as a command-line tool does when it refuses its arguments.
     */
    suspend fun start(command: List<String>, workingDirectory: String): Either<Unit, EditorError>

    /** Shows [path] in the system's file manager, selected where the system can, or else its folder. */
    suspend fun reveal(path: String): Either<Unit, EditorError>
}

/** How Leaf opens a file in the editor of a [EditorChoice]. */
sealed interface EditorOpening {
    data class Run(val command: List<String>) : EditorOpening

    data class WithDefaultApp(val file: String) : EditorOpening

    data class Refused(val error: EditorError) : EditorOpening
}

/**
 * How to open [target] with [choice], from the worktree [worktree]. Nothing chosen ([EditorChoice.None]) opens with the
 * default app: the menus ask which editor before that.
 */
fun editorOpening(choice: EditorChoice, target: EditorTarget, worktree: String, isMac: Boolean): EditorOpening =
    when (choice) {
        is EditorChoice.Installed -> editorCommand(choice.editor, target, isMac)?.let(EditorOpening::Run)
            ?: EditorOpening.Refused(EditorError.NotInstalled(choice.editor.name))

        is EditorChoice.NotInstalled -> EditorOpening.Refused(EditorError.NotInstalled(choice.name))

        is EditorChoice.Custom -> customEditorCommand(choice.command, target, worktree)?.let(EditorOpening::Run)
            ?: EditorOpening.Refused(EditorError.NoCustomCommand)

        EditorChoice.DefaultApp, EditorChoice.None -> EditorOpening.WithDefaultApp(target.file)
    }
