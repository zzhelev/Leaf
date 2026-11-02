// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.ui.status.AmendedFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry

/**
 * The files of the commit that Amend rewrites, for the Staged pane's group (fork-only, upstream issue 260). [target] is
 * that commit, or null while nothing is amended. It is read when the commit changes, never when the status does: a
 * commit's files don't change, so a refresh while Amend is on reads nothing. Another commit (HEAD moved from a terminal)
 * reads again, and the files of the one before stay until they arrive.
 */
class AmendedFilesController(
    scope: CoroutineScope,
    target: Flow<Commit?>,
    private val load: suspend (Commit) -> Either<List<DiffEntry>, AppError>,
) {
    /** The commit's files, or null while nothing is amended. */
    val files: StateFlow<AmendedFiles?>
        field = MutableStateFlow<AmendedFiles?>(null)

    private val retries = MutableStateFlow(0)

    init {
        scope.launch {
            combine(target.distinctUntilChanged { a, b -> a?.hash == b?.hash }, retries) { commit, _ -> commit }
                .collectLatest { commit -> read(commit) }
        }
    }

    /** Reads the files again, after a failure. */
    fun retry() = retries.update { it + 1 }

    private suspend fun read(commit: Commit?) {
        if (commit == null) {
            files.value = null
            return
        }

        files.value = AmendedFiles(commit, changes = files.value?.changes.orEmpty(), isLoading = true)

        files.value = when (val result = load(commit)) {
            is Either.Ok -> AmendedFiles(commit, changes = result.value)
            is Either.Err -> AmendedFiles(commit, error = result.error)
        }
    }
}
