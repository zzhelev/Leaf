// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.common.printError
import dev.app.leaf.domain.sorting.RefFolderExpansion
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import java.io.File

/**
 * Side panel folders the user opened or closed, kept in the per-repository Leaf config file. It lives in the common
 * git dir, so every worktree of a repository shares it.
 */
internal object RefFolderExpansionConfig {
    private const val SECTION = "sidePanel"
    private const val EXPANDED = "expandedFolder"
    private const val COLLAPSED = "collapsedFolder"

    fun load(repository: Repository): RefFolderExpansion {
        val file = commonConfigFile(repository)

        if (!file.exists()) return RefFolderExpansion()

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return RefFolderExpansion()

        return RefFolderExpansion(
            expanded = config.getStringList(SECTION, null, EXPANDED).toSet(),
            collapsed = config.getStringList(SECTION, null, COLLAPSED).toSet(),
        )
    }

    fun save(repository: Repository, expansion: RefFolderExpansion) {
        val file = commonConfigFile(repository)
        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections, such as sign-off

        config.setOrUnset(EXPANDED, expansion.expanded)
        config.setOrUnset(COLLAPSED, expansion.collapsed)
        config.save()
    }

    private fun FileBasedConfig.setOrUnset(name: String, values: Set<String>) {
        if (values.isEmpty()) {
            unset(SECTION, null, name)
        } else {
            setStringList(SECTION, null, name, values.sorted())
        }
    }
}

/**
 * The per-repository Leaf config file in the common git dir, which every worktree of the repository shares. In the main
 * worktree it is also the file that holds sign-off.
 */
internal fun commonConfigFile(repository: Repository) =
    File(repository.commonDirectory ?: repository.directory, LocalConfigConstants.CONFIG_FILE_NAME)

private const val TAG = "RefFolderExpansionConfig"

/**
 * Loads the file, or leaves the config empty when the file can't be parsed, so a broken file is replaced on the next
 * save instead of blocking it. Returns whether the file was read.
 */
internal fun FileBasedConfig.loadOrStartEmpty(): Boolean = try {
    load()
    true
} catch (ex: ConfigInvalidException) {
    printError(TAG, "Ignoring unreadable ${file.absolutePath}", ex)
    clear()
    false
}
