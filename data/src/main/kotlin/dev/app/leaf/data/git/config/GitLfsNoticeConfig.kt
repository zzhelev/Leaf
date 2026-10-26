// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.domain.lfs.GitLfsNotice
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig

/**
 * The git-lfs notice that the user dismissed (fork-only), in the per-repository Leaf config file in the common git dir,
 * so that every worktree's tab leaves it out. Another notice, as after installing git-lfs without setting it up, shows.
 */
internal object GitLfsNoticeConfig {
    private const val SECTION = "lfs"
    private const val DISMISSED = "dismissedNotice"

    fun dismissed(repository: Repository): GitLfsNotice? {
        val file = commonConfigFile(repository)

        if (!file.exists()) return null

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return null

        return GitLfsNotice.ofKey(config.getString(SECTION, null, DISMISSED))
    }

    fun dismiss(repository: Repository, notice: GitLfsNotice) {
        val file = commonConfigFile(repository)
        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections and keys
        config.setString(SECTION, null, DISMISSED, notice.key)
        config.save()
    }
}
