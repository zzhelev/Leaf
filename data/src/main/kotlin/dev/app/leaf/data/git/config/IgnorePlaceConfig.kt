// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.domain.ignore.IgnorePlace
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig

/**
 * The place that ignore rules went to last (fork-only), in the per-repository Leaf config file in the common git dir,
 * so that every worktree's tab shares it. The ignore dialog starts with it.
 */
internal object IgnorePlaceConfig {
    private const val SECTION = "ignore"
    private const val PLACE = "place"
    private const val GITIGNORE = "gitignore"
    private const val EXCLUDE = "exclude"

    fun load(repository: Repository): IgnorePlace {
        val file = commonConfigFile(repository)

        if (!file.exists()) return IgnorePlace.GITIGNORE

        val config = FileBasedConfig(file, repository.fs)

        if (!config.loadOrStartEmpty()) return IgnorePlace.GITIGNORE

        return if (config.getString(SECTION, null, PLACE) == EXCLUDE) IgnorePlace.EXCLUDE else IgnorePlace.GITIGNORE
    }

    /**
     * Remembers [place], except the global list: it affects every repository, so the dialog doesn't start with it
     * because it was used once, and the place of the repository stays what it was.
     */
    fun save(repository: Repository, place: IgnorePlace) {
        if (place == IgnorePlace.GLOBAL) return

        val file = commonConfigFile(repository)
        file.createNewFile()

        val config = FileBasedConfig(file, repository.fs)
        config.loadOrStartEmpty() // Keep the other sections and keys
        config.setString(SECTION, null, PLACE, if (place == IgnorePlace.EXCLUDE) EXCLUDE else GITIGNORE)
        config.save()
    }
}
