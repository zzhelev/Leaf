// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.editor

import dev.app.leaf.domain.errors.EditorError
import dev.app.leaf.domain.errors.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Starting an editor's program (fork-only): what counts as started, and what a failure says. */
@DisabledOnOs(OS.WINDOWS)
class EditorLauncherTest {
    @TempDir
    lateinit var temp: File

    private fun script(name: String, body: String) = File(temp, name).apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    @Test
    fun `a program gets its arguments as they are, in the folder, with the variables added`() {
        val recorded = File(temp, "args")
        val program = script("editor", "printf '%s\\n' \"\$PWD\" \"\$LEAF_TEST\" \"\$@\" > '${recorded.path}'")
        val folder = File(temp, "work tree").apply { mkdirs() }

        val result = startAndCheck(
            listOf(program.path, "--goto", "/a path/with spaces.kt:3", ""),
            folder,
            mapOf("LEAF_TEST" to "added"),
            checkMillis = 3_000,
        )

        assertEquals(Either.Ok(Unit), result)
        assertEquals(
            listOf(folder.canonicalPath, "added", "--goto", "/a path/with spaces.kt:3", ""),
            recorded.readLines().let { listOf(File(it[0]).canonicalPath) + it.drop(1) },
        )
    }

    @Test
    fun `a program that ends at once with an error code fails with what it said`() {
        val program = script("refuses", "echo \"unrecognized option: --goto\" >&2\nexit 3")

        val result = startAndCheck(listOf(program.path, "--goto", "a"), temp, emptyMap(), checkMillis = 3_000)

        assertEquals(
            Either.Err(EditorError.Failed("${program.path} --goto a", 3, "unrecognized option: --goto")),
            result,
        )
    }

    @Test
    fun `a program that goes on running counts as started, and is left running`() {
        val done = File(temp, "done")
        val program = script("ide", "sleep 2\ntouch '${done.path}'\nexit 1")
        val started = System.nanoTime()

        val result = startAndCheck(listOf(program.path), temp, emptyMap(), checkMillis = 300)

        assertEquals(Either.Ok(Unit), result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "didn't wait for it")

        val deadline = System.currentTimeMillis() + 10_000
        while (!done.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(done.exists(), "it went on after Leaf stopped waiting")
    }

    @Test
    fun `a program that can't start fails without an exit code`() {
        val result = startAndCheck(listOf(File(temp, "missing").path, "x"), temp, emptyMap(), checkMillis = 3_000)

        val error = (result as Either.Err).error as EditorError.Failed
        assertEquals(null, error.exitCode)
        assertEquals("${File(temp, "missing").path} x", error.command)
    }

    @Test
    fun `with no time to check, only a failure to start counts`() {
        val program = script("explorer", "exit 1")

        assertEquals(Either.Ok(Unit), startAndCheck(listOf(program.path), temp, emptyMap(), checkMillis = 0))
    }

    @Test
    fun `a program that reads its input gets none, and doesn't hang`() {
        val program = script("reader", "cat > /dev/null\nexit 4")

        val result = startAndCheck(listOf(program.path), temp, emptyMap(), checkMillis = 3_000)

        assertEquals(4, ((result as Either.Err).error as EditorError.Failed).exitCode)
    }
}
