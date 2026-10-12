// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.CheckBranchNameGitAction
import dev.app.leaf.data.git.config.GetWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.config.SaveWorktreeBaseBranchGitAction
import dev.app.leaf.data.git.config.SaveWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.models.WorktreeInclude
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The git actions behind creating worktrees, against a repository made with the git CLI that ignores `.env`,
 * `local.properties`, `build/` and `config/`, and has an agent's worktree inside it, as Claude Code places them.
 */
@DisabledOnOs(OS.WINDOWS)
class NewWorktreeGitActionsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val jgit = testJGit()

    private val main by lazy { File(tempDir, "main") }
    private val mainGitDir by lazy { File(main, ".git").path }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        main.mkdirs()
        git.run(main, "init")
        File(main, ".gitignore").writeText(".env\nlocal.properties\nbuild/\nconfig/\n.claude/worktrees/\n")
        File(main, "tracked.env").writeText("tracked\n")
        git.run(main, "add", ".")
        git.run(main, "commit", "-m", "Initial commit")

        write(".env", "SECRET=1\n")
        write("local.properties", "sdk.dir=/sdk\n")
        write("build/out.o", "out\n")
        write("config/secrets.json", "{}\n")
        write("sub/.env", "SUB=1\n")
        write("untracked.env", "not ignored\n")
        git.run(main, "worktree", "add", File(main, ".claude/worktrees/agent1").path, "-b", "agent1")
        write(".claude/worktrees/agent1/.env", "AGENT=1\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists the ignored files that worktreeinclude matches, not tracked or unignored ones or other worktrees'`(): Unit =
        runBlocking {
            write(".worktreeinclude", ".env\n*.env\nlocal.properties\nconfig/secrets.json\n")

            val include = GetWorktreeIncludeGitAction(gitCli)(main.path)

            assertEquals(
                Either.Ok(
                    WorktreeInclude(
                        File(main, ".worktreeinclude").path,
                        listOf(".env", "config/secrets.json", "local.properties", "sub/.env"),
                    )
                ),
                include,
            )
        }

    @Test
    fun `a folder pattern lists the files in it`(): Unit = runBlocking {
        write(".worktreeinclude", "build/\n")

        val include = (GetWorktreeIncludeGitAction(gitCli)(main.path) as Either.Ok).value

        assertEquals(listOf("build/out.o"), include?.files)
    }

    @Test
    fun `without worktreeinclude there's nothing to read`(): Unit = runBlocking {
        assertEquals(Either.Ok(null), GetWorktreeIncludeGitAction(gitCli)(main.path))
    }

    @Test
    fun `a worktreeinclude that matches no ignored file lists none`(): Unit = runBlocking {
        write(".worktreeinclude", "# nothing yet\nuntracked.env\n")

        val include = (GetWorktreeIncludeGitAction(gitCli)(main.path) as Either.Ok).value

        assertEquals(emptyList<String>(), include?.files)
    }

    @Test
    fun `copies files with their folders, and never over a file, through a link or out of the worktree`(): Unit =
        runBlocking {
            val target = File(tempDir, "new")
            File(target, "existing").mkdirs()
            File(target, "local.properties").writeText("already here\n")
            File(tempDir, "outside.txt").writeText("outside\n")
            File(main, "linked.env").symlinkTo(File(tempDir, "outside.txt"))
            write("linked-folder/escaped.txt", "escaped\n")
            target.resolve("linked-folder").symlinkTo(tempDir)

            val result = CopyWorktreeFilesGitAction()(
                main.path,
                target.path,
                listOf(
                    ".env",
                    "config/secrets.json",
                    "local.properties",
                    "linked.env",
                    "missing.env",
                    "../outside.txt",
                    "linked-folder/escaped.txt",
                ),
            )

            assertEquals(Either.Ok(Unit), result)
            assertEquals("SECRET=1\n", File(target, ".env").readText())
            assertEquals("{}\n", File(target, "config/secrets.json").readText())
            assertEquals("already here\n", File(target, "local.properties").readText())
            assertFalse(File(target, "linked.env").exists())
            assertFalse(File(target, "missing.env").exists())
            assertFalse(File(tempDir, "escaped.txt").exists())
        }

    @Test
    fun `git decides which branch names are valid`(): Unit = runBlocking {
        val check = CheckBranchNameGitAction(gitCli)

        assertEquals(Either.Ok(true), check(mainGitDir, "claude/fix-auth"))
        assertEquals(Either.Ok(false), check(mainGitDir, "bad..name"))
        assertEquals(Either.Ok(false), check(mainGitDir, "with space"))
        assertEquals(Either.Ok(false), check(mainGitDir, "-x"))
        assertEquals(Either.Ok(false), check(mainGitDir, "HEAD"))

        git.run(main, "checkout", "-q", "-b", "other")
        git.run(main, "checkout", "-q", "main")
        // git would turn it into the branch checked out before
        assertEquals(Either.Ok(false), check(mainGitDir, "@{-1}"))
    }

    @Test
    fun `saves how new worktrees are made, next to the base branch, and reads it from any worktree`(): Unit =
        runBlocking {
            val agentGitDir = File(main, ".git/worktrees/agent1").path
            SaveWorktreeBaseBranchGitAction(jgit)(mainGitDir, "refs/heads/main")

            assertEquals(Either.Ok(WorktreeCreationSettings()), GetWorktreeCreationSettingsGitAction(jgit)(mainGitDir))

            SaveWorktreeCreationSettingsGitAction(jgit)(agentGitDir, WorktreeCreationSettings("/agents", false))

            assertEquals(
                Either.Ok(WorktreeCreationSettings("/agents", false)),
                GetWorktreeCreationSettingsGitAction(jgit)(mainGitDir),
            )
            val config = File(main, ".git/leaf").readText()
            assertTrue("baseBranch = refs/heads/main" in config, config)

            SaveWorktreeCreationSettingsGitAction(jgit)(mainGitDir, WorktreeCreationSettings(null, true))

            assertEquals(Either.Ok(WorktreeCreationSettings()), GetWorktreeCreationSettingsGitAction(jgit)(agentGitDir))
        }

    @Test
    fun `creates a worktreeinclude that explains itself, and keeps one that's there`(): Unit = runBlocking {
        val created = CreateWorktreeIncludeGitAction()(main.path)

        val file = File(main, ".worktreeinclude")
        assertEquals(Either.Ok(file.path), created)
        assertEquals(WORKTREE_INCLUDE_TEMPLATE, file.readText())

        file.writeText(".env\n")
        CreateWorktreeIncludeGitAction()(main.path)

        assertEquals(".env\n", file.readText())
    }

    private fun write(path: String, text: String) {
        val file = File(main, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
