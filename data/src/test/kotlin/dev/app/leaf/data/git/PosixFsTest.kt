// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.errors.AbortedByHookException
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Hooks run by JGit through Leaf's [JGit] cache, which opens repositories with [PosixFs] on macOS and Linux. */
@DisabledOnOs(OS.WINDOWS)
class PosixFsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir get() = File(repository, ".git").absolutePath
    private val tools by lazy { createHookTool(File(tempDir, "tools")) }
    private val shellPath get() = "${tools.absolutePath}:${System.getenv("PATH")}"

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private fun addHook(name: String, content: String) {
        File(repository, ".git/hooks/$name").writeExecutable(content)
    }

    private suspend fun commit(jgit: JGit): Either<RevCommit, GitError> = jgit.provide(gitDir) { git ->
        File(repository, "file.txt").appendText("change\n")
        git.add().addFilepattern("file.txt").call()
        git.commit().setMessage("Change").setSign(false).call()
    }

    @Test
    fun `a hook finds a tool that is only on the login shell's PATH`(): Unit = runBlocking {
        addHook("pre-commit", HOOK_RUNNING_TOOL)

        val result = commit(testJGit(mapOf("PATH" to shellPath)))

        assertInstanceOf(Either.Ok::class.java, result)
        assertTrue(File(tools, "ran").exists())
    }

    @Test
    fun `without the login shell's PATH the same hook fails`(): Unit = runBlocking {
        addHook("pre-commit", HOOK_RUNNING_TOOL)

        val result = commit(testJGit())

        val error = (result as Either.Err).error as GenericError
        assertInstanceOf(AbortedByHookException::class.java, error.exception)
        assertFalse(File(tools, "ran").exists())
    }

    @Test
    fun `hooks get the shell's other variables, but JGit's own variables win`(): Unit = runBlocking {
        val output = File(tempDir, "hook-output.txt")
        addHook(
            "pre-commit",
            "#!/bin/sh\nprintf '%s\\n%s\\n' \"\$GIT_DIR\" \"\$LEAF_SHELL_VARIABLE\" > \"\$LEAF_HOOK_OUTPUT\"\n",
        )
        val shellVariables = mapOf(
            "GIT_DIR" to "/not/the/repository",
            "LEAF_SHELL_VARIABLE" to "from the shell",
            "LEAF_HOOK_OUTPUT" to output.absolutePath,
        )

        val result = commit(testJGit(shellVariables))

        assertInstanceOf(Either.Ok::class.java, result)
        assertEquals(listOf(File(gitDir).canonicalPath, "from the shell"), output.readLines().map { it.canonicalOr() })
    }

    @Test
    fun `a repository first opened for a fetch still runs hooks with the shell's PATH`(): Unit = runBlocking {
        addHook("pre-commit", HOOK_RUNNING_TOOL)
        val jgit = testJGit(mapOf("PATH" to shellPath))

        // Fetches and pushes open the repository through provideOptional, and every later operation reuses it
        jgit.provideOptional(gitDir) { }
        val result = commit(jgit)

        assertInstanceOf(Either.Ok::class.java, result)
        assertTrue(File(tools, "ran").exists())
    }

    /** Resolves `/var` and `/private/var` on macOS to the same path, leaving other lines as they are. */
    private fun String.canonicalOr(): String = if (startsWith("/")) File(this).canonicalPath else this
}
