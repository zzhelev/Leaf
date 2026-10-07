// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.repositories.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The sizes of the status pane's sections, shared by every tab and kept in the preferences like the pane widths. */
@Singleton
class StatusSectionsConfig @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    val sizes: StateFlow<StatusSectionSizes>
        field = MutableStateFlow(appSettingsRepository.statusSectionSizes)

    fun save(sizes: StatusSectionSizes) {
        this.sizes.value = sizes
        appSettingsRepository.statusSectionSizes = sizes
    }
}
