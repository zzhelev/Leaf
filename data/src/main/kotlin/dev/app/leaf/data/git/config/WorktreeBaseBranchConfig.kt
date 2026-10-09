// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig

/**
 * The branch chosen as the base of the repository's worktrees, kept in the per-repository Leaf config file. It lives in
 * the common git dir, so every worktree of a repository shares it. Without it, Leaf picks the base by itself.
 */
internal object WorktreeBaseBranchConfig {
    private const val SECTION = "worktrees"
    private const val BASE_BRANCH = "baseBranch"

    /**
     * The chosen branch (`refs/heads/develop`, `refs/remotes/origin/main`). Null when none is chosen, or the file holds
     * something other than a local or remote branch, or can't be read.
     */
    fun load(repository: Repository): String? {
        val file = commonConfigFile(repository)

        if (!file.exists()) return null

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return null

        return config.getString(SECTION, null, BASE_BRANCH)?.takeIf { isBranch(it) }
    }

    /** Chooses [branch], or removes the choice when it's null. */
    fun save(repository: Repository, branch: String?) {
        require(branch == null || isBranch(branch)) { "Not a local or remote branch: $branch" }

        val file = commonConfigFile(repository)

        if (branch == null && !file.exists()) return

        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections, such as sign-off and the side panel's folders

        if (branch == null) {
            config.unset(SECTION, null, BASE_BRANCH)

            // JGit keeps the section's header otherwise
            if (config.getNames(SECTION).isEmpty()) {
                config.unsetSection(SECTION, null)
            }
        } else {
            config.setString(SECTION, null, BASE_BRANCH, branch)
        }

        config.save()
    }

    /**
     * After the branch [oldName] was renamed to [newName] (both `refs/heads/x`), chooses [newName] if [oldName] was
     * chosen, as git moves a branch's `branch.<name>` config along. Returns whether it did.
     */
    fun renamed(repository: Repository, oldName: String, newName: String): Boolean {
        if (load(repository) != oldName) return false

        save(repository, newName)
        return true
    }

    private fun isBranch(name: String) = listOf(Constants.R_HEADS, Constants.R_REMOTES).any { prefix ->
        name.length > prefix.length && name.startsWith(prefix)
    }
}
