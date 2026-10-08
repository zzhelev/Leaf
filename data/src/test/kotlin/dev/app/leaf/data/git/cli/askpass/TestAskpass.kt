// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsState
import dev.app.leaf.domain.credentials.CredentialsStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

/**
 * Leaf's askpass helper as the build copies it into the app's resources (`./gradlew :app:rustTasks`), or null when it
 * isn't built. Tests that run it are skipped then.
 */
fun builtAskpassHelper(): File? = File("../app/src/main/resources", ASKPASS_HELPER_NAME)
    .takeIf { it.isFile && it.canExecute() }

/**
 * Plays the user while [block] runs: answers each of Leaf's dialogs (a [CredentialsRequest]) in turn with the next of
 * [answers], and closes the dialogs that come after them. Returns what [block] returned and the dialogs shown.
 */
suspend fun <T> CredentialsStateManager.answeringDialogs(
    vararg answers: CredentialsStateManager.(CredentialsRequest) -> Unit,
    block: suspend () -> T,
): Pair<T, List<CredentialsRequest>> = coroutineScope {
    val requests = Collections.synchronizedList(mutableListOf<CredentialsRequest>())

    val user = launch(Dispatchers.Default) {
        var answered: CredentialsState? = null

        for (index in 0..Int.MAX_VALUE) {
            // Each request is a new instance, even when it equals the one before
            val request = credentialsState.first { it is CredentialsRequest && it !== answered } as CredentialsRequest
            answered = request
            requests.add(request)

            val answer = answers.getOrNull(index)

            if (answer == null) {
                credentialsDenied()
            } else {
                answer(request)
            }
        }
    }

    try {
        block() to requests.toList()
    } finally {
        user.cancel()
    }
}
