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
 *     showFolder = claude
 *     hide = refs/heads/claude/old-try
 * ```
 *
 * `branches` is `all` (with a `hide` per branch left out, a `hideFolder` per folder, and a `show` per branch of such a
 * folder shown all the same), `current`, or `only` (with a `show` per branch, a `showFolder` per folder, and a `hide`
 * per branch of such a folder left out). No section is every branch. A value it doesn't know, a name that isn't a local
 * or remote branch, or a folder with a `/` or a space, is left out.
 */
internal object LogBranchFilterConfig {
    private const val SECTION = "log"
    private const val BRANCHES = "branches"
    private const val HIDE = "hide"
    private const val SHOW = "show"
    private const val HIDE_FOLDER = "hideFolder"
    private const val SHOW_FOLDER = "showFolder"

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
        fun folders(key: String) = config.getStringList(SECTION, null, key).filterTo(LinkedHashSet()) { isFolder(it) }

        return when (config.getString(SECTION, null, BRANCHES)) {
            CURRENT -> LogBranchFilter.Current
            ONLY -> LogBranchFilter.Only(names(SHOW), folders(SHOW_FOLDER), names(HIDE))
            ALL -> LogBranchFilter.All(names(HIDE), folders(HIDE_FOLDER), names(SHOW))
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
                config.setNames(SHOW, filter.shown)
                config.setFolders(SHOW_FOLDER, filter.shownFolders)
                config.setNames(HIDE, filter.hiddenInShownFolders)
            }

            is LogBranchFilter.All -> {
                config.setString(SECTION, null, BRANCHES, ALL)
                config.setNames(HIDE, filter.hidden)
                config.setFolders(HIDE_FOLDER, filter.hiddenFolders)
                config.setNames(SHOW, filter.shownInHiddenFolders)
            }
        }

        config.save()
    }

    /**
     * After the branch [oldName] was renamed to [newName] (both `refs/heads/x`), names [newName] where the filter named
     * [oldName], as git moves a branch's `branch.<name>` config along. Returns whether it did.
     */
    fun renamed(repository: Repository, oldName: String, newName: String): Boolean {
        fun Set<String>.renamed() = mapTo(LinkedHashSet()) { name -> if (name == oldName) newName else name }

        val filter = load(repository)
        val renamed = when (filter) {
            is LogBranchFilter.All -> filter.copy(
                hidden = filter.hidden.renamed(),
                shownInHiddenFolders = filter.shownInHiddenFolders.renamed(),
            )

            is LogBranchFilter.Only -> filter.copy(
                shown = filter.shown.renamed(),
                hiddenInShownFolders = filter.hiddenInShownFolders.renamed(),
            )

            LogBranchFilter.Current -> filter
        }

        if (renamed == filter) return false

        save(repository, renamed)
        return true
    }

    private fun FileBasedConfig.setNames(key: String, names: Set<String>) {
        val valid = names.filter { isBranch(it) }

        if (valid.isNotEmpty()) setStringList(SECTION, null, key, valid)
    }

    private fun FileBasedConfig.setFolders(key: String, folders: Set<String>) {
        val valid = folders.filter { isFolder(it) }

        if (valid.isNotEmpty()) setStringList(SECTION, null, key, valid)
    }

    /** The side panel's folders are one level: a name's part before its first `/`. */
    private fun isFolder(name: String) = name.isNotEmpty() && name.none { it == '/' || it.isWhitespace() }

    private fun isBranch(name: String) = listOf(Constants.R_HEADS, Constants.R_REMOTES).any { prefix ->
        name.length > prefix.length && name.startsWith(prefix)
    }
}
