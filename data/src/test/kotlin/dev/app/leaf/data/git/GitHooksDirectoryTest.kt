// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.data.shell.LoginShellEnvironment
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Where Leaf's commits find their hooks ([GitHooksDirectory], through [PosixFs]), compared with `git commit`, which
 * runs the same hooks. git and JGit read the same user config, and JGit skips its `includeIf` (upstream issue 186).
 */
@DisabledOnOs(OS.WINDOWS)
class GitHooksDirectoryTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    /** What the login shell adds: git reads [globalConfig] and no system config, and `~` is [home]. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to home.path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        )
    }

    private val home by lazy { File(root, "home") }
    private val gitCli by lazy { testGitCli(shellVariables) }

    /** Each hook that runs adds a line with its name and the folder it is in. */
    private val hookLog by lazy { File(root, "hooks.log") }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.com\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `runs the hooks of a folder that an includeIf file sets, as git commit does`(): Unit = runBlocking {
        val hooks = File(root, "work-hooks")
        val workConfig = File(root, "work.gitconfig").apply { writeText("[core]\n\thooksPath = ${hooks.path}\n") }
        globalConfig.appendText("[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${workConfig.path}\n")
        val repository = initRepository(File(root, "work/repo"))
        addHook(hooks, "pre-commit")
        addHook(hooks, "commit-msg")
        addHook(hooks, "post-commit")

        // JGit alone doesn't see the folder
        commitByLeaf(repository, testJGit())
        assertEquals(emptyList<String>(), runHooks())

        commitByLeaf(repository)
        val byLeaf = runHooks()
        commitByGit(repository)

        assertEquals(listOf("pre-commit", "commit-msg", "post-commit").map { "$it in ${hooks.path}" }, byLeaf)
        assertEquals(byLeaf + byLeaf, runHooks())
    }

    @Test
    fun `expands ~ in core hooksPath, as git does`(): Unit = runBlocking {
        val repository = initRepository(File(root, "repo"))
        git(repository, "config", "core.hooksPath", "~/hooks")
        val hooks = File(home, "hooks")
        addHook(hooks, "pre-commit")

        commitByLeaf(repository)
        commitByGit(repository)

        assertEquals(List(2) { "pre-commit in ${hooks.path}" }, runHooks())
    }

    @Test
    fun `a relative core hooksPath from an includeIf file is in each worktree's own folder`(): Unit = runBlocking {
        val included = File(root, "included.gitconfig").apply { writeText("[core]\n\thooksPath = .husky/_\n") }
        globalConfig.appendText("[includeIf \"gitdir:${root.path}/\"]\n\tpath = ${included.path}\n")
        val repository = initRepository(File(root, "repo"))
        val worktree = File(root, "feature")
        git(repository, "worktree", "add", "-q", "-b", "feature", worktree.path)
        addHook(File(repository, ".husky/_"), "pre-commit")
        addHook(File(worktree, ".husky/_"), "pre-commit")
        val worktreeGitDir = File(git(worktree, "rev-parse", "--absolute-git-dir").trim())

        commitByLeaf(worktree, gitDir = worktreeGitDir)
        commitByGit(worktree)

        assertEquals(List(2) { "pre-commit in ${File(worktree, ".husky/_").path}" }, runHooks())
    }

    @Test
    fun `without core hooksPath, a linked worktree runs the common git dir's hooks, as git does`(): Unit =
        runBlocking {
            val repository = initRepository(File(root, "repo"))
            val worktree = File(root, "feature")
            git(repository, "worktree", "add", "-q", "-b", "feature", worktree.path)
            val hooks = File(repository, ".git/hooks")
            addHook(hooks, "pre-commit")
            val worktreeGitDir = File(git(worktree, "rev-parse", "--absolute-git-dir").trim())

            commitByLeaf(worktree, gitDir = worktreeGitDir)
            commitByGit(worktree)

            assertEquals(List(2) { "pre-commit in ${hooks.path}" }, runHooks())
        }

    @Test
    fun `skips a hook that isn't executable, as git does`(): Unit = runBlocking {
        val repository = initRepository(File(root, "repo"))
        val hooks = File(root, "hooks")
        git(repository, "config", "core.hooksPath", hooks.path)
        addHook(hooks, "pre-commit")
        File(hooks, "pre-commit").setExecutable(false)

        commitByLeaf(repository)
        commitByGit(repository)

        assertEquals(emptyList<String>(), runHooks())
    }

    @Test
    fun `without a usable git, finds hooks as JGit does, as before`(): Unit = runBlocking {
        val repository = initRepository(File(root, "repo"))
        val hooks = File(repository, ".git/hooks")
        addHook(hooks, "pre-commit")
        val withoutGit = testGitCli(shellVariables, configuredPath = File(root, "missing/git").path)

        commitByLeaf(repository, testJGit(hooksDirectory = GitHooksDirectory(withoutGit)))

        assertEquals(listOf("pre-commit in ${hooks.path}"), runHooks())
    }

    @Test
    fun `a new instance of the file system still finds hooks where git does`() {
        val repository = initRepository(File(root, "repo"))
        val hooks = File(root, "hooks")
        addHook(hooks, "pre-commit")
        val fs = PosixFs(LoginShellEnvironment { emptyMap() }, hooksDirectory = HooksDirectory { hooks }).newInstance()

        Git.open(File(repository, ".git"), fs).use { git ->
            File(repository, "file.txt").appendText("change\n")
            git.add().addFilepattern("file.txt").call()
            git.commit().setMessage("By Leaf").setSign(false).call()
        }

        assertEquals(listOf("pre-commit in ${hooks.path}"), runHooks())
    }

    /** A hook named [name] in [folder] that adds its line to [hookLog]. */
    private fun addHook(folder: File, name: String) {
        val folderOfHook = "\$(cd \"\$(dirname \"\$0\")\" && pwd -P)"
        File(folder, name).writeExecutable("#!/bin/sh\necho \"$name in $folderOfHook\" >> '$hookLog'\n")
    }

    /** The lines that the hooks that ran added, in order. */
    private fun runHooks(): List<String> = if (hookLog.exists()) hookLog.readLines() else emptyList()

    private fun initRepository(directory: File): File {
        directory.mkdirs()
        git(directory, "init", "-q", "-b", "main")

        return directory
    }

    /** Commits a change in [workTree] through Leaf's [JGit], which finds hooks with [GitHooksDirectory]. */
    private suspend fun commitByLeaf(
        workTree: File,
        jgit: JGit = testJGit(hooksDirectory = GitHooksDirectory(gitCli)),
        gitDir: File = File(workTree, ".git"),
    ) {
        val result: Either<RevCommit, GitError> = jgit.provide(gitDir.path) { git ->
            File(workTree, "file.txt").appendText("change\n")
            git.add().addFilepattern("file.txt").call()
            git.commit().setMessage("By Leaf").setSign(false).call()
        }

        assertInstanceOf(Either.Ok::class.java, result, "Leaf's commit failed: $result")
    }

    private fun commitByGit(workTree: File) {
        File(workTree, "file.txt").appendText("change\n")
        git(workTree, "add", "file.txt")
        git(workTree, "commit", "-q", "-m", "By git")
    }

    /** Runs git as Leaf runs it, with [shellVariables]. */
    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
