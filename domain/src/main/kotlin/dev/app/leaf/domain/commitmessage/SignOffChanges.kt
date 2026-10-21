// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import dev.app.leaf.common.TabScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Counts the saves of the tab's sign-off setting (fork-only), which the Actions list's dialog and the commit message's
 * ⋯ menu both make, so that the menu's check and the title row's tag follow either. The file watcher ignores Leaf's own
 * `leaf` file, where the setting is.
 */
@TabScope
class SignOffChanges @Inject constructor() {
    val count: StateFlow<Int>
        field = MutableStateFlow(0)

    fun changed() {
        count.value++
    }
}
