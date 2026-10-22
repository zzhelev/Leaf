// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.models.CenterSplitSizes
import dev.app.leaf.domain.repositories.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** How the centre pane splits between the log and an open diff, shared by every tab and kept in the preferences like the pane widths. */
@Singleton
class CenterSplitConfig @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    val sizes: StateFlow<CenterSplitSizes>
        field = MutableStateFlow(appSettingsRepository.centerSplitSizes)

    fun save(sizes: CenterSplitSizes) {
        this.sizes.value = sizes
        appSettingsRepository.centerSplitSizes = sizes
    }
}
