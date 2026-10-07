// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.CommitChangesSectionSizes
import dev.app.leaf.domain.repositories.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The size of the Files changed pane's commit message, shared by every tab and kept in the preferences. */
@Singleton
class CommitChangesSectionsConfig @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    val sizes: StateFlow<CommitChangesSectionSizes>
        field = MutableStateFlow(appSettingsRepository.commitChangesSectionSizes)

    fun save(sizes: CommitChangesSectionSizes) {
        this.sizes.value = sizes
        appSettingsRepository.commitChangesSectionSizes = sizes
    }
}
