// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.domain.tabs.TabLookStore
import dev.app.leaf.domain.tabs.tabColorOf
import dev.app.leaf.domain.tabs.tabNameOf
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import java.io.File
import javax.inject.Inject

/**
 * The name and colour that the user gave a repository's tabs (fork-only, upstream issue 242), in the per-repository
 * Leaf config file in the git dir that the worktrees share, as `[tab] name` and `color`, the number of a graph lane. A
 * value that can't be read counts as none, so the tab shows its folder's name.
 */
class TabLookConfig @Inject constructor() : TabLookStore {
    override fun load(repositoryGitDir: String): TabLook {
        val file = configFile(repositoryGitDir)

        if (!file.isFile) return TabLook()

        val config = FileBasedConfig(file, FS.DETECTED)

        if (!config.loadOrStartEmpty()) return TabLook()

        return TabLook(
            name = tabNameOf(config.getString(SECTION, null, NAME)),
            color = tabColorOf(config.getString(SECTION, null, COLOR)?.trim()?.toIntOrNull()),
        )
    }

    override fun save(repositoryGitDir: String, look: TabLook) {
        val file = configFile(repositoryGitDir)
        file.createNewFile()

        val config = FileBasedConfig(file, FS.DETECTED)
        config.loadOrStartEmpty() // Keep the other sections, such as sign-off

        val name = tabNameOf(look.name)
        val color = tabColorOf(look.color)

        if (name == null && color == null) {
            config.unsetSection(SECTION, null)
        } else {
            if (name == null) config.unset(SECTION, null, NAME) else config.setString(SECTION, null, NAME, name)
            if (color == null) config.unset(SECTION, null, COLOR) else config.setInt(SECTION, null, COLOR, color)
        }

        config.save()
    }

    override fun fileOf(repositoryGitDir: String): String = configFile(repositoryGitDir).path

    private fun configFile(repositoryGitDir: String) = File(repositoryGitDir, LocalConfigConstants.CONFIG_FILE_NAME)

    private companion object {
        const val SECTION = "tab"
        const val NAME = "name"
        const val COLOR = "color"
    }
}
