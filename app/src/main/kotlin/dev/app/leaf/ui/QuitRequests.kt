// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A tab's conflict resolver file with changes that aren't saved, which quitting would lose: picks, or lines typed.
 * [tabName] and [worktreeName] name the tab, as its tab and the window title do.
 */
class UnsavedResolverFile(
    val path: String,
    val tabName: StateFlow<String?>,
    val worktreeName: StateFlow<String?>,
    /** Writes the file, then calls `onSaved`, or `onFailed` when it couldn't, which the resolver's strip then says. */
    val save: (onSaved: () -> Unit, onFailed: () -> Unit) -> Unit,
    /** Selects the file's tab. */
    val show: () -> Unit,
)

/** What the quit dialog asks about. */
class QuitPrompt(val files: List<UnsavedResolverFile>)

/**
 * Quitting Leaf (fork-only): the window's close button, and on macOS Cmd+Q, the Dock's Quit, and logging out. When a
 * conflict resolver has changes that aren't saved, the quit waits for the user's answer to [prompt]: save them first,
 * quit without them, or stay.
 *
 * The callbacks of a file's save can come on any thread. A quit asked for while another waits for its answer or for its
 * saves is left to that one: one answer covers both.
 */
class QuitRequests {
    val prompt: StateFlow<QuitPrompt?>
        field = MutableStateFlow<QuitPrompt?>(null)

    private class Request(val files: List<UnsavedResolverFile>, val quit: () -> Unit, val cancelled: () -> Unit)

    /** The quit that waits for its answer or its saves. */
    private var pending: Request? = null

    /**
     * Quits ([quit]) at once when no file in [unsaved] has changes that aren't saved, and asks first otherwise.
     * [cancelled] runs when the user stays, or a save fails.
     */
    fun request(unsaved: List<UnsavedResolverFile>, quit: () -> Unit, cancelled: () -> Unit = {}) {
        synchronized(this) {
            if (pending != null) {
                return
            }

            if (unsaved.isNotEmpty()) {
                pending = Request(unsaved, quit, cancelled)
                prompt.value = QuitPrompt(unsaved)
                return
            }
        }

        quit()
    }

    /** The answer to [prompt]: save the files first, then quit ([save] true), quit without them (false), or stay (null). */
    fun answer(save: Boolean?) {
        val request = synchronized(this) {
            // Answered already: the saves run
            if (prompt.value == null) {
                return
            }

            prompt.value = null
            pending
        } ?: return

        when (save) {
            null -> end(request, quit = false)
            false -> end(request, quit = true)
            true -> saveInTurn(request, request.files)
        }
    }

    /** Saves [files] one after another, then quits. A save that fails shows its tab and stops the quit. */
    private fun saveInTurn(request: Request, files: List<UnsavedResolverFile>) {
        val file = files.firstOrNull() ?: return end(request, quit = true)

        file.save(
            { if (isPending(request)) saveInTurn(request, files.drop(1)) },
            {
                if (isPending(request)) {
                    file.show()
                    end(request, quit = false)
                }
            },
        )
    }

    private fun isPending(request: Request) = synchronized(this) { pending === request }

    private fun end(request: Request, quit: Boolean) {
        synchronized(this) {
            if (pending !== request) {
                return
            }

            pending = null
        }

        if (quit) request.quit() else request.cancelled()
    }
}
