// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.CloneCheckoutError
import dev.app.leaf.domain.errors.LfsDownloadFirstError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import dev.app.leaf.domain.errors.LfsFilterError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the error dialog says when Leaf couldn't download LFS objects (fork-only): before git started, where nothing
 * changed, or as git's filter, where git stopped halfway. The files are grouped by why, five at most each.
 */
class LfsErrorTextTest {
    private val refused = LfsError.ObjectRefused(404, "Object does not exist")

    @Test
    fun `a download that failed before git started says that nothing changed`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = LfsDownloadFirstError(
            listOf(
                LfsFileFailure("b.bin", refused),
                LfsFileFailure("art/c.psd", LfsError.CredentialsDenied),
                LfsFileFailure("copy.bin", refused),
            )
        )

        assertEquals(
            listOf(
                "Leaf couldn't download the Git LFS files that this needs, so it didn't start, and nothing changed.",
                "Couldn't download the LFS object: the LFS server answered 404 Object does not exist.\n\n" +
                    "b.bin\ncopy.bin",
                "Couldn't download the LFS object: the LFS server's credentials weren't given.\n\nart/c.psd",
            ).joinToString("\n\n"),
            textOf(error),
        )
    }

    @Test
    fun `a download that stopped git says to discard what it wrote, and counts the files past five`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val error = LfsFilterError((1..7).map { LfsFileFailure("f$it.bin", refused) }, output = "")

            assertEquals(
                listOf(
                    "git stopped, as Leaf couldn't download the Git LFS files it was writing.",
                    "Couldn't download the LFS object: the LFS server answered 404 Object does not exist.\n\n" +
                        "f1.bin\nf2.bin\nf3.bin\nf4.bin\nf5.bin\nand 2 more",
                    "The files that git wrote before it stopped are left in the working tree, as changes. Discard " +
                        "them before you try again.",
                ).joinToString("\n\n"),
                textOf(error),
            )
        }

    @Test
    fun `a clone whose files couldn't be downloaded says that it was removed, not to discard them`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val error = CloneCheckoutError(LfsFilterError(listOf(LfsFileFailure("clone/b.bin", refused)), output = ""))

            assertEquals(
                listOf(
                    "git cloned the repository but couldn't check out its files, so Leaf removed the clone.",
                    "Couldn't download the LFS object: the LFS server answered 404 Object does not exist.\n\n" +
                        "clone/b.bin",
                ).joinToString("\n\n"),
                textOf(error),
            )
        }

    @Test
    fun `a clone that git-lfs couldn't check out shows git's output after it`(): Unit = runBlocking(Dispatchers.Swing) {
        val output = "fatal: b.bin: smudge filter lfs failed"
        val error = CloneCheckoutError(RemoteOperationError.Failed(128, output))

        val text = textOf(error)

        val removed = "git cloned the repository but couldn't check out its files, so Leaf removed the clone."
        assertTrue(text.startsWith("$removed\n\n")) { text }
        assertTrue(text.endsWith(output)) { text }
    }

    private fun textOf(error: AppError): String {
        var text = ""
        val scene = ImageComposeScene(width = 400, height = 100, density = Density(1f)) {
            AppTheme { text = error.getErrorText() }
        }
        scene.render()
        scene.close()

        return text
    }
}
