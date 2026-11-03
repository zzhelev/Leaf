// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig

/**
 * Which branches the log shows (fork-only, upstream issue 207), kept in the per-repository Leaf config file. It lives in
 * the common git dir, so every worktree of a repository shares it, as the worktrees' base branch:
 *
 * ```
 * [log]
 *     branches = only
 *     show = refs/heads/main
 *     show = refs/heads/claude/parser-cache
 * ```
 *
 * `branches` is `all` (with a `hide` per branch left out), `current` or `only` (with a `show` per branch). No section is
 * every branch. A value it doesn't know, or a name that isn't a local or remote branch, is left out.
 */
internal object LogBranchFilterConfig {
    private const val SECTION = "log"
    private const val BRANCHES = "branches"
    private const val HIDE = "hide"
    private const val SHOW = "show"

    private const val ALL = "all"
    private const val CURRENT = "current"
    private const val ONLY = "only"

    /** The saved filter, or every branch when none is saved, or the file can't be read. */
    fun load(repository: Repository): LogBranchFilter {
        val file = commonConfigFile(repository)

        if (!file.exists()) return NO_LOG_BRANCH_FILTER

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return NO_LOG_BRANCH_FILTER

        fun names(key: String) = config.getStringList(SECTION, null, key).filterTo(LinkedHashSet()) { isBranch(it) }

        return when (config.getString(SECTION, null, BRANCHES)) {
            CURRENT -> LogBranchFilter.Current
            ONLY -> LogBranchFilter.Only(names(SHOW))
            ALL -> LogBranchFilter.All(names(HIDE))
            else -> NO_LOG_BRANCH_FILTER
        }
    }

    /** Saves [filter], or removes the section for every branch. */
    fun save(repository: Repository, filter: LogBranchFilter) {
        val file = commonConfigFile(repository)

        if (filter == NO_LOG_BRANCH_FILTER && !file.exists()) return

        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections, such as sign-off and the worktrees' base branch
        config.unsetSection(SECTION, null)

        when (filter) {
            NO_LOG_BRANCH_FILTER -> Unit
            LogBranchFilter.Current -> config.setString(SECTION, null, BRANCHES, CURRENT)
            is LogBranchFilter.Only -> {
                config.setString(SECTION, null, BRANCHES, ONLY)
                config.setStringList(SECTION, null, SHOW, filter.shown.filter { isBranch(it) })
            }

            is LogBranchFilter.All -> {
                config.setString(SECTION, null, BRANCHES, ALL)
                config.setStringList(SECTION, null, HIDE, filter.hidden.filter { isBranch(it) })
            }
        }

        config.save()
    }

    /**
     * After the branch [oldName] was renamed to [newName] (both `refs/heads/x`), names [newName] where the filter named
     * [oldName], as git moves a branch's `branch.<name>` config along. Returns whether it did.
     */
    fun renamed(repository: Repository, oldName: String, newName: String): Boolean {
        val renamed = when (val filter = load(repository)) {
            is LogBranchFilter.All -> filter.takeIf { oldName in it.hidden }?.let {
                LogBranchFilter.All(it.hidden.map { name -> if (name == oldName) newName else name }.toSet())
            }

            is LogBranchFilter.Only -> filter.takeIf { oldName in it.shown }?.let {
                LogBranchFilter.Only(it.shown.map { name -> if (name == oldName) newName else name }.toSet())
            }

            LogBranchFilter.Current -> null
        } ?: return false

        save(repository, renamed)
        return true
    }

    private fun isBranch(name: String) = listOf(Constants.R_HEADS, Constants.R_REMOTES).any { prefix ->
        name.length > prefix.length && name.startsWith(prefix)
    }
}
