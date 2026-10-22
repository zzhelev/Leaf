// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.repositories.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the branches pane is folded away, shared by every tab and kept in the preferences with the pane widths, so that
 * the choice survives a restart. The pane's width is kept while it is folded, and comes back when it opens.
 */
@Singleton
class BranchesPaneConfig @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    val isFolded: StateFlow<Boolean>
        field = MutableStateFlow(appSettingsRepository.branchesPaneFolded)

    fun setFolded(folded: Boolean) {
        isFolded.value = folded
        appSettingsRepository.branchesPaneFolded = folded
    }

    fun toggle() = setFolded(!isFolded.value)
}
