// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.domain.models.WorktreeCreationSettings
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig

/**
 * How new worktrees of the repository are made (fork-only), kept in the per-repository Leaf config file in the common
 * git dir, so that every worktree's tab shares it: the folder they go in, and whether the tab switches to them.
 */
internal object WorktreeCreationConfig {
    private const val SECTION = "worktrees"
    private const val FOLDER = "newWorktreeFolder"
    private const val SWITCH_TO_NEW = "switchToNewWorktree"

    fun load(repository: Repository): WorktreeCreationSettings {
        val defaults = WorktreeCreationSettings()
        val file = commonConfigFile(repository)

        if (!file.exists()) return defaults

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return defaults

        return WorktreeCreationSettings(
            folder = config.getString(SECTION, null, FOLDER)?.takeIf { it.isNotBlank() },
            switchToNew = config.getBoolean(SECTION, null, SWITCH_TO_NEW, defaults.switchToNew),
        )
    }

    fun save(repository: Repository, settings: WorktreeCreationSettings) {
        val file = commonConfigFile(repository)
        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections and keys, such as the base branch

        val folder = settings.folder

        if (folder == null) {
            config.unset(SECTION, null, FOLDER)
        } else {
            config.setString(SECTION, null, FOLDER, folder)
        }

        config.setBoolean(SECTION, null, SWITCH_TO_NEW, settings.switchToNew)
        config.save()
    }
}
