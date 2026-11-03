// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.worktrees.repositoryGitDirOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TabLooks"

/** Where the repositories' [TabLook]s are kept, by the git dir that a repository's worktrees share. */
interface TabLookStore {
    /** The look saved for the repository whose shared git dir is [repositoryGitDir], with no name or colour that can't be read. */
    @Throws(IOException::class)
    fun load(repositoryGitDir: String): TabLook

    /** Saves [look] for that repository, keeping its other settings. */
    @Throws(IOException::class)
    fun save(repositoryGitDir: String, look: TabLook)

    /** The file that [save] writes, for an error to name. */
    fun fileOf(repositoryGitDir: String): String
}

/**
 * The looks of the repositories that tabs show (fork-only, upstream issue 242), one for each repository, so that every
 * tab of a repository, whichever worktree it shows, follows a change made in any of them. Each is read once, when a tab
 * first asks for it.
 */
@Singleton
class TabLooks @Inject constructor(
    private val store: TabLookStore,
) {
    private val looks = MutableStateFlow<Map<String, TabLook>>(emptyMap())
    private val changes = Mutex()

    /** The look of the repository at [path], a git dir or a working tree of any of its worktrees. */
    fun lookOf(path: String): Flow<TabLook> = flow {
        val key = repositoryGitDirOf(path)

        if (key !in looks.value) {
            val loaded = loadFrom(key)
            looks.update { if (key in it) it else it + (key to loaded) }
        }

        emitAll(looks.map { it[key] ?: TabLook() })
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /** Changes the look of the repository at [path] and saves it. Nothing changes when it can't be saved. */
    suspend fun update(path: String, change: (TabLook) -> TabLook): Either<TabLook, AppError> =
        withContext(Dispatchers.IO) {
            changes.withLock {
                val key = repositoryGitDirOf(path)
                val current = looks.value[key] ?: loadFrom(key)
                val changed = change(current)

                if (changed != current) {
                    try {
                        store.save(key, changed)
                    } catch (e: IOException) {
                        printError(TAG, "Couldn't save the tab look of $key", e)

                        return@withContext Either.Err(
                            GenericError("Leaf couldn't write\n\n${store.fileOf(key)}\n\n${e.message ?: e.javaClass.simpleName}", e)
                        )
                    }
                }

                looks.update { it + (key to changed) }
                Either.Ok(changed)
            }
        }

    private fun loadFrom(key: String): TabLook = try {
        store.load(key)
    } catch (e: IOException) {
        printError(TAG, "Couldn't read the tab look of $key", e)
        TabLook()
    }
}
