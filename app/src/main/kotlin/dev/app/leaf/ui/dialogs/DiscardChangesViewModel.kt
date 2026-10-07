// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.usecases.DiscardEntriesUseCase

/** Discards the unstaged changes of [entries] once the user confirms. */
class DiscardChangesViewModel @AssistedInject constructor(
    private val discardEntriesUseCase: DiscardEntriesUseCase,
    @Assisted private val entries: List<StatusEntry>,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(entries: List<StatusEntry>): DiscardChangesViewModel
    }

    fun discard() {
        discardEntriesUseCase(entries, isStaged = false)
    }
}
