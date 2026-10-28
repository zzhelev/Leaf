// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.operations.AbortSquashMergeGitAction
import dev.app.leaf.data.git.cli.operations.Autostashes
import dev.app.leaf.data.git.cli.operations.PutBackMergeAutostashGitAction
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.cli.remote.testGitCliRemoteCommand
import dev.app.leaf.data.git.conflicts.OpenMergeToolGitAction
import dev.app.leaf.data.git.conflicts.ResolveConflictGitAction
import dev.app.leaf.data.git.conflicts.ResolveRenamedFileGitAction
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.rewrite.RewriteBranchGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.worktrees.RunGitCommandGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.AutostashPutBack
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.RewriteGroup
import dev.app.leaf.domain.rewrite.RewriteKind
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

private const val A1 = "a.bin, version 1\n"
private const val A2 = "a.bin, version 2\n"
private const val A3 = "a.bin, version 3\n"
private const val B1 = "b.bin, version 1\n"
private const val M1 = "m.bin, version 1\n"
private const val MINE = "a.bin, as I changed it\n"

/**
 * The local git commands that Leaf runs and that may check out or stage files, through Leaf's LFS filter where git-lfs
 * isn't set up (fork-only, stage 4 of `docs/fork/lfs-filter.md`): the worktree steps, the conflict actions, the merge
 * tool, putting autostashed changes back, undoing a squash merge, and a squash onto the latest main. Without the
 * filter, git wrote the pointers into the working tree, and `git add` staged a file's content in place of its pointer.
 * The LFS objects are on [FakeLfsServer], and git runs with a PATH without git-lfs. Skipped without the helper.
 */
@DisabledOnOs(OS.WINDOWS)
// git waits for the filter's answers, so a broken conversation would hang rather than fail
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LfsFilterLocalCommandsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var helper: File
    private lateinit var globalConfig: File
    private lateinit var git: TestGitCli
    private lateinit var server: FakeLfsServer
    private lateinit var serverStorage: File
    private lateinit var repo: File
    private lateinit var remote: TestRemoteCommand

    private val gitDir get() = File(repo, ".git").path

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!

        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        globalConfig = File(tempDir, "test.gitconfig").apply {
            writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        }
        git = TestGitCli(globalConfig)
        serverStorage = File(tempDir, "lfs-server")
        server = FakeLfsServer(serverStorage)
        val withoutGitLfs = pathWithoutGitLfs(File(tempDir, "bin").apply { mkdirs() })
        remote = TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to withoutGitLfs), jgit = jgit)

        repo = File(tempDir, "repo")
        git.run(tempDir, "init", "-q", "-b", "main", repo.path)
        File(repo, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        git.run(repo, "config", "lfs.url", server.url)
        commit("Start", "README.md" to "LFS test\n", "a.bin" to A1)
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a worktree step's git worktree add writes the new worktree's LFS files`(): Unit = runBlocking {
        featureChanging("a.bin" to A2, "b.bin" to B1)
        checkOutThroughLeaf()
        val worktree = File(tempDir, "wt")

        runGitCommand(listOf("worktree", "add", "-q", worktree.path, "feature")).ok()

        assertEquals(A2, File(worktree, "a.bin").readText())
        assertEquals(B1, File(worktree, "b.bin").readText())
        // One store for every worktree
        assertTrue(File(repo, ".git/lfs/objects/${objectPath(B1)}").isFile)
    }

    @Test
    fun `a worktree step's squash merge writes the LFS files and stages their pointers`(): Unit = runBlocking {
        featureChanging("a.bin" to A2, "b.bin" to B1)
        checkOutThroughLeaf()

        runGitCommand(listOf("merge", "--squash", "feature")).ok()

        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(B1, File(repo, "b.bin").readText())
        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
    }

    @Test
    fun `Use theirs writes theirs' LFS file and stages its pointer`(): Unit = runBlocking {
        conflictInA()

        resolveConflict(ConflictSide.THEIRS, keepWorkingFile = false).ok()

        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
    }

    @Test
    fun `keeping the working file stages its pointer, and its content goes to the store`(): Unit = runBlocking {
        conflictInA()
        File(repo, "a.bin").writeText(MINE)

        resolveConflict(ConflictSide.OURS, keepWorkingFile = true).ok()

        assertEquals(MINE, File(repo, "a.bin").readText())
        assertEquals(pointer(MINE), git.run(repo, "cat-file", "-p", ":a.bin"))
        assertEquals(MINE, File(repo, ".git/lfs/objects/${objectPath(MINE)}").readText())
    }

    @Test
    fun `a file renamed on both sides stages the pointers of the names kept`(): Unit = runBlocking {
        renamedOnBothSides()

        val resolve = ResolveRenamedFileGitAction(jgit, remote.gitCli, remote.command)
        resolve(gitDir, RenamedFile("a.bin", "b.bin", "c.bin"), ConflictSide.entries.toSet()).ok()

        assertEquals(pointer(A1), git.run(repo, "cat-file", "-p", ":b.bin"))
        assertEquals(pointer(A1), git.run(repo, "cat-file", "-p", ":c.bin"))
        assertEquals(A1, File(repo, "b.bin").readText())
    }

    @Test
    fun `a renamed file's name kept, whose working file is gone, is checked out as content`(): Unit = runBlocking {
        renamedOnBothSides()
        File(repo, "c.bin").delete()

        val resolve = ResolveRenamedFileGitAction(jgit, remote.gitCli, remote.command)
        resolve(gitDir, RenamedFile("a.bin", "b.bin", "c.bin"), setOf(ConflictSide.THEIRS)).ok()

        assertEquals(A1, File(repo, "c.bin").readText())
        assertEquals(pointer(A1), git.run(repo, "cat-file", "-p", ":c.bin"))
    }

    @Test
    fun `the merge tool gets the LFS files' content, and what it saves is staged as a pointer`(): Unit = runBlocking {
        conflictInA()
        git.run(repo, "config", "merge.tool", "leaf")
        git.run(repo, "config", "mergetool.leaf.cmd", "cp \"\$REMOTE\" \"\$MERGED\"")
        git.run(repo, "config", "mergetool.leaf.trustExitCode", "true")

        val openMergeTool = OpenMergeToolGitAction(jgit, remote.gitCli, remote.command, remote.stateRepository)
        openMergeTool(gitDir, "a.bin", turn = null).ok()

        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
    }

    @Test
    fun `changes that merge_autoStash set aside come back as content, not as pointers`(): Unit = runBlocking {
        featureChanging("b.bin" to B1)
        // A fast-forward puts the changes back at once (git 2.54), a merge only when it's committed or aborted
        commit("Main moves on", "README.md" to "main moved on\n")
        checkOutThroughLeaf()
        git.run(repo, "config", "merge.autoStash", "true")
        File(repo, "a.bin").writeText(MINE)
        runGitCommand(listOf("merge", "--squash", "feature")).ok()
        assertEquals(A1, File(repo, "a.bin").readText())

        val putBack = PutBackMergeAutostashGitAction(autostashes(), jgit)

        assertEquals(Either.Ok(AutostashPutBack.Applied), putBack(gitDir, AutostashPutBackAfter.COMMIT))
        assertEquals(MINE, File(repo, "a.bin").readText())
    }

    @Test
    fun `undoing a squash merge writes the LFS files back as content`(): Unit = runBlocking {
        featureChanging("a.bin" to A2)
        checkOutThroughLeaf()
        runGitCommand(listOf("merge", "--squash", "feature")).ok()
        assertEquals(A2, File(repo, "a.bin").readText())

        AbortSquashMergeGitAction(autostashes(), remote.command, jgit)(gitDir).ok()

        assertEquals(A1, File(repo, "a.bin").readText())
        assertEquals(pointer(A1), git.run(repo, "cat-file", "-p", ":a.bin"))
    }

    @Test
    fun `a squash onto the latest main writes main's LFS files as content`(): Unit = runBlocking {
        featureChanging("b.bin" to B1)
        val feature = git.run(repo, "rev-parse", "feature").trim()
        commit("Add m.bin on main", "m.bin" to M1)
        git.run(repo, "switch", "-q", "feature")
        checkOutThroughLeaf()

        val rewrite = RewriteBranchGitAction(jgit, remote.gitCli, remote.command, remote.stateRepository)
        val onto = OntoBase("refs/heads/main", git.run(repo, "rev-parse", "main").trim())
        val group = RewriteGroup(listOf(feature), "Add b.bin")
        remote.progress.clear()
        rewrite(gitDir, BranchRewrite("refs/heads/feature", feature, listOf(group), RewriteKind.SQUASH, onto)).ok()

        assertEquals(M1, File(repo, "m.bin").readText())
        assertEquals(B1, File(repo, "b.bin").readText())
        // No progress on the processing screen while m.bin downloads, whose Cancel would leave the files half moved
        assertEquals(emptyList<TaskProgress?>(), remote.progress.toList())
    }

    @Test
    fun `the merge tool's label gives way to an LFS download's progress, and comes back`(): Unit = runBlocking {
        // Large enough to arrive in several parts: a small object's only progress is 100%, which shows the label
        val theirs = "a.bin, version 2\n".repeat(100_000)
        conflictInA(theirs)
        git.run(repo, "config", "merge.tool", "leaf")
        git.run(repo, "config", "mergetool.leaf.cmd", "cp \"\$REMOTE\" \"\$MERGED\"")
        git.run(repo, "config", "mergetool.leaf.trustExitCode", "true")
        val shown = Collections.synchronizedList(mutableListOf<TaskProgress?>())
        val stateRepository = object : RepositoryStateRepository by remote.stateRepository {
            override fun updateTaskProgress(progress: TaskProgress?) {
                shown.add(progress)
                remote.stateRepository.updateTaskProgress(progress)
            }
        }

        // The base's and theirs' objects aren't in the store: the checkout before the merge wrote ours
        OpenMergeToolGitAction(jgit, remote.gitCli, remote.command, stateRepository)(gitDir, "a.bin", turn = null).ok()

        val label = TaskProgress(stage = "leaf: a.bin", percent = null)
        val downloads = shown.indexOfFirst { it?.stage?.startsWith("Downloading LFS objects") == true }
        assertEquals(theirs, File(repo, "a.bin").readText())
        assertEquals(label, shown.first())
        assertTrue(downloads > 0) { "${shown.distinct()}" }
        assertEquals(listOf(label, null), shown.takeLast(2))
    }

    @Test
    fun `a local command that git refuses fails with git's message`(): Unit = runBlocking {
        val result = runGitCommand(listOf("checkout", "no-such-branch"))

        val error = assertInstanceOf(GitCliError.CommandFailed::class.java, (result as Either.Err).error)
        assertEquals("git checkout no-such-branch", error.command)
        assertEquals(1, error.exitCode)
        assertTrue("did not match" in error.stderr) { error.stderr }
    }

    @Test
    fun `without the askpass helper, the local commands run as before, without the filter`(): Unit = runBlocking {
        featureChanging("a.bin" to A2)
        val withoutHelper = testGitCliRemoteCommand(remote.gitCli, jgit, helper = null)

        RunGitCommandGitAction(remote.gitCli, withoutHelper)(repo.path, listOf("merge", "--squash", "feature")).ok()

        // As git has no LFS filter, the LFS file is the pointer that the commit holds
        assertEquals(pointer(A2), File(repo, "a.bin").readText())
    }

    private suspend fun runGitCommand(args: List<String>) =
        RunGitCommandGitAction(remote.gitCli, remote.command)(repo.path, args)

    private suspend fun resolveConflict(side: ConflictSide, keepWorkingFile: Boolean) =
        ResolveConflictGitAction(jgit, remote.gitCli, remote.command)(gitDir, listOf("a.bin"), side, keepWorkingFile)

    private fun autostashes() = Autostashes(remote.gitCli, remote.command, remote.stateRepository)

    /** `feature` with [files], from `main`, which stays checked out. */
    private fun featureChanging(vararg files: Pair<String, String>) {
        git.run(repo, "switch", "-q", "-c", "feature")
        commit("Feature", *files)
        git.run(repo, "switch", "-q", "main")
    }

    /** A merge of `feature`, which renamed `a.bin` to `c.bin`, into `main`, which renamed it to `b.bin`. */
    private suspend fun renamedOnBothSides() {
        git.run(repo, "switch", "-q", "-c", "feature")
        git.run(repo, "mv", "a.bin", "c.bin")
        git.run(repo, "commit", "-q", "-m", "Rename a.bin to c.bin")
        git.run(repo, "switch", "-q", "main")
        git.run(repo, "mv", "a.bin", "b.bin")
        git.run(repo, "commit", "-q", "-m", "Rename a.bin to b.bin")
        checkOutThroughLeaf()
        remote.command.run(repo, listOf("merge", "--no-edit", "feature"))
        assertEquals(A1, File(repo, "c.bin").readText())
    }

    /**
     * A merge of `feature` into `main` that stops at a conflict in `a.bin`: ours is [A3], theirs [theirs]. git merges
     * the two pointers as text, so the working file is a pointer whose `oid` lines are between conflict markers, as
     * with git-lfs.
     */
    private suspend fun conflictInA(theirs: String = A2) {
        featureChanging("a.bin" to theirs)
        commit("Main", "a.bin" to A3)
        checkOutThroughLeaf()

        val merge = (remote.command.run(repo, listOf("merge", "--no-edit", "feature")) as Either.Ok).value

        assertEquals(1, merge.exitCode, merge.stderr)
        assertTrue("<<<<<<<" in File(repo, "a.bin").readText()) { File(repo, "a.bin").readText() }
    }

    /**
     * Commits [files] with git, which has no LFS filter here: an LFS file goes in as its pointer, and its content to
     * the server.
     */
    private fun commit(message: String, vararg files: Pair<String, String>) {
        for ((name, content) in files) {
            if (name.endsWith(".bin")) {
                File(repo, name).writeText(pointer(content))
                File(serverStorage, oid(content)).writeText(content)
            } else {
                File(repo, name).writeText(content)
            }
        }

        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", message)
    }

    /** Writes the working tree's LFS files again through Leaf's filter, as Leaf's clone leaves them. */
    private suspend fun checkOutThroughLeaf() {
        repo.listFiles { file -> file.name.endsWith(".bin") }!!.forEach { it.delete() }
        val output = (remote.command.run(repo, listOf("checkout", "--", ".")) as Either.Ok).value
        assertEquals(0, output.exitCode, output.stderr)
    }

    private fun <T> Either<T, *>.ok(): T {
        assertInstanceOf(Either.Ok::class.java, this) { "$this" }

        return (this as Either.Ok).value
    }

    private fun pointer(content: String) =
        lfsPointerText(LongObjectId.fromString(oid(content)), content.length.toLong())

    private fun oid(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun objectPath(content: String) = oid(content).let { "${it.substring(0, 2)}/${it.substring(2, 4)}/$it" }
}
