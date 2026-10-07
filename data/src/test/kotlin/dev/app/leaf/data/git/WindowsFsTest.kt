// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.AbortedByHookException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Hooks run by [WindowsFs] through JGit's own commit and push commands. On macOS and Linux, `/bin/sh` stands in for
 * Git Bash: both read the hook as a shell script.
 */
@DisabledOnOs(OS.WINDOWS)
class WindowsFsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val windowsFs = WindowsFs { GitBash("/bin/sh", quoteArgument = { it }) }
    private val hookOutput = ByteArrayOutputStream()

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    private fun addHook(name: String, script: String, hooksDir: File = File(repository, ".git/hooks")) {
        File(hooksDir, name).writeExecutable("#!/bin/sh\n$script\n")
    }

    private fun <T> withGit(gitDir: File = File(repository, ".git"), fs: FS = windowsFs, block: (Git) -> T): T {
        return Git.open(gitDir, fs).use(block)
    }

    private fun Git.commitChange(): RevCommit {
        File(repository.workTree, "file.txt").appendText("change\n")
        add().addFilepattern("file.txt").call()

        return commit()
            .setMessage("Change")
            .setSign(false)
            .setHookOutputStream(PrintStream(hookOutput))
            .setHookErrorStream(PrintStream(ByteArrayOutputStream()))
            .call()
    }

    @Test
    fun `a hook that succeeds lets the commit through`() {
        val ran = File(tempDir, "ran")
        addHook("pre-commit", "touch '$ran'")

        val commit = withGit { it.commitChange() }

        assertEquals("Change", commit.fullMessage)
        assertTrue(ran.exists())
    }

    @Test
    fun `a failing hook blocks the commit with its exit code and error output`() {
        addHook("pre-commit", "echo 'Lint failed' >&2\nexit 3")

        val error = assertThrows<AbortedByHookException> { withGit { it.commitChange() } }

        assertEquals("pre-commit", error.hookName)
        assertEquals(3, error.returnCode)
        assertEquals("Lint failed\n", error.hookStdErr)
    }

    @Test
    fun `commit-msg gets the message file and can rewrite the message`() {
        addHook("commit-msg", "printf 'Rewritten, read from %s\\n' \"\$1\" > \"\$1\"")

        val commit = withGit { it.commitChange() }

        assertEquals("Rewritten, read from .git/COMMIT_EDITMSG\n", commit.fullMessage)
    }

    @Test
    fun `pre-push gets the remote as arguments and the refs on its input`() {
        val remote = File(tempDir, "remote.git")
        git.run(tempDir, "init", "--bare", remote.absolutePath)
        git.run(repository, "remote", "add", "origin", remote.absolutePath)
        val received = File(tempDir, "pre-push.txt")
        addHook("pre-push", "printf '%s\\n' \"\$@\" > '$received'\ncat >> '$received'")

        withGit { it.push().setRemote("origin").add("main").call() }

        val head = git.run(repository, "rev-parse", "HEAD").trim()
        val zeroId = "0".repeat(40)
        assertEquals(
            listOf("origin", remote.absolutePath, "refs/heads/main $head refs/heads/main $zeroId"),
            received.readLines(),
        )
    }

    @Test
    @Timeout(60)
    fun `a hook that prints more than a pipe holds does not hang`() {
        val size = 1024 * 1024
        addHook("pre-commit", "head -c $size /dev/zero | tr '\\0' o\nhead -c $size /dev/zero | tr '\\0' e >&2")

        withGit { it.commitChange() }

        assertEquals(size, hookOutput.size())
    }

    @Test
    fun `runs the hooks in core hooksPath, relative to the working tree`() {
        val ran = File(tempDir, "ran")
        git.run(repository, "config", "core.hooksPath", ".husky/_")
        addHook("pre-commit", "touch '$ran'", hooksDir = File(repository, ".husky/_"))
        addHook("pre-commit", "exit 1")

        withGit { it.commitChange() }

        assertTrue(ran.exists())
    }

    @Test
    fun `a linked worktree runs the shared hooks in its own folder, with JGit's variables`() {
        val worktree = File(tempDir, "feature")
        git.run(repository, "worktree", "add", worktree.absolutePath, "-b", "feature")
        val output = File(tempDir, "hook-environment.txt")
        addHook(
            "pre-commit",
            "printf '%s\\n' \"\$(pwd -P)\" \"\$GIT_DIR\" \"\$GIT_COMMON_DIR\" \"\$GIT_WORK_TREE\" > '$output'",
        )
        val worktreeGitDir = File(repository, ".git/worktrees/feature")

        withGit(gitDir = worktreeGitDir) { it.commitChange() }

        assertEquals(
            listOf(worktree, worktreeGitDir, File(repository, ".git"), worktree).map { it.canonicalPath },
            output.readLines().map { File(it).canonicalPath },
        )
    }

    @Test
    fun `a hook without Git Bash fails with a clear error`() {
        addHook("pre-commit", "exit 0")

        val error = assertThrows<JGitInternalException> {
            withGit(fs = WindowsFs { null }) { it.commitChange() }
        }

        assertEquals("Git Bash was not found. It is needed to run the hook 'pre-commit' on Windows", error.message)
    }

    @Test
    fun `without hooks, Git Bash is not looked for`() {
        var lookups = 0

        withGit(fs = WindowsFs { lookups++; null }) { it.commitChange() }

        assertEquals(0, lookups)
    }

    @Test
    fun `a new instance still runs hooks with Git Bash`() {
        val ran = File(tempDir, "ran")
        addHook("pre-commit", "touch '$ran'")
        val copy = windowsFs.newInstance()

        withGit(fs = copy) { it.commitChange() }

        assertInstanceOf(WindowsFs::class.java, copy)
        assertTrue(ran.exists())
    }
}
