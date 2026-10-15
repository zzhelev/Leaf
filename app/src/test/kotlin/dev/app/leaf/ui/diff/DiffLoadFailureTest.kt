// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.MissingObjectError
import dev.app.leaf.domain.errors.PartialCloneFetchError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val OFFLINE = "fatal: unable to access 'https://example.com/app.git/': Could not resolve host: example.com"

/** What the diff pane shows in place of a diff that couldn't be loaded, and that "Try again" loads it again. */
class DiffLoadFailureTest {
    @Test
    fun `a partial clone that couldn't download the file says why`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = PartialCloneFetchError("origin", RemoteOperationError.ConnectionFailed(OFFLINE))

        Failure(error).use { failure ->
            assertEquals(
                listOf(
                    "Leaf couldn't download this file from origin.",
                    "The repository is a partial clone, which downloads files when they're first needed.\n\n" +
                        "Couldn't connect to the remote.\n\n$OFFLINE",
                    "Try again",
                ),
                failure.texts(),
            )

            failure.click("Try again")

            assertEquals(listOf("try again"), failure.calls)
        }
    }

    @Test
    fun `an object missing from another repository shows JGit's message`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = MissingObjectError("626799f0f85326a8c1fc522db584e86cdfccd51f", "Missing blob 626799f0f85326a8c1fc")

        Failure(error).use { failure ->
            assertEquals(
                listOf(
                    "Leaf couldn't show this file's changes.",
                    "The repository doesn't have an object that this needs. Missing blob 626799f0f85326a8c1fc",
                    "Try again",
                ),
                failure.texts(),
            )
        }
    }

    private class Failure(error: AppError) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 1000, height = 400, density = Density(1f)) {
            AppTheme {
                DiffLoadFailure(error, onTryAgain = { calls += "try again" })
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun click(text: String) {
            val button = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(button.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}
