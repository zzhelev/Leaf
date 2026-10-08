// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import dev.app.leaf.updates.Update
import dev.app.leaf.updates.UpdateCheck
import dev.app.leaf.updates.UpdatesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Checks for updates when the dialog opens, and again when the user asks. */
class CheckForUpdatesViewModel @Inject constructor(
    private val updatesRepository: UpdatesRepository,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
) : TabViewModel() {
    /** What the last check found, or null while a check runs. */
    val result: StateFlow<UpdateCheck?>
        field = MutableStateFlow<UpdateCheck?>(null)

    private var checking: Job? = null

    init {
        check()
    }

    fun check() {
        if (checking?.isActive == true) {
            return
        }

        result.value = null
        checking = viewModelScope.launch {
            result.value = updatesRepository.checkNow()
        }
    }

    fun openDownloadPage(update: Update) = openUrlInBrowserUseCase(update.downloadUrl)
}
