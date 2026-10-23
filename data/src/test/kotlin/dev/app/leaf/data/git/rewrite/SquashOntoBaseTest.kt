// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.RewriteGroup
import dev.app.leaf.domain.rewrite.RewriteKind
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val FEAT = "refs/heads/feat"
private const val MAIN = "refs/heads/main"

/**
 * A squash of the whole branch onto the latest main (fork-only, step A4 of `docs/fork/squash-and-rebase.md`), against
 * git: the one commit's tree is the one that `git rebase main` ends with, a checked-out branch's files take main's
 * changes and keep the uncommitted ones, what is in the way or conflicts is refused, changing nothing, and Undo puts
 * the files back. Then the check that the dialog makes when it opens.
 */
@DisabledOnOs(OS.WINDOWS)
class SquashOntoBaseTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val remote by lazy {
        TestRemoteCommand(helper = checkNotNull(builtAskpassHelper()), globalConfig = globalConfig, jgit = jgit)
    }
    private val rewriteBranch by lazy {
        RewriteBranchGitAction(jgit, remote.gitCli, remote.command, remote.stateRepository)
    }
    private val undoRewrite by lazy { UndoBranchRewriteGitAction(jgit, remote.gitCli) }
    private val checkOnto by lazy { GetOntoBaseCheckGitAction(jgit, remote.gitCli) }

    private lateinit var work: File
    private lateinit var feature: List<String>
    private var clock = 1_700_000_000L

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        assumeTrue(builtAskpassHelper() != null, "Leaf's askpass helper isn't built")
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = File(root, "work")
        git.run(root, "init", "-q", "-b", "main", work.path)
        commit("Base", "a.txt" to "a\n", "shared.txt" to "one\ntwo\nthree\n")

        git.run(work, "switch", "-q", "-c", "feat")
        feature = listOf(
            commit("Add the parser", "b.txt" to "b\n", author = "Bea Writer <bea@example.invalid>"),
            commit("wip", "c.txt" to "c\n"),
            commit("Cache the parsed files", "shared.txt" to "one\ntwo\nthree, cached\n"),
        )

        git.run(work, "switch", "-q", "main")
        commit("Fix the release notes", "notes.txt" to "notes\n")
        commit("Change the first line", "shared.txt" to "one, changed\ntwo\nthree\n")
        git.run(work, "switch", "-q", "feat")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun commit(
        message: String,
        vararg files: Pair<String, String>,
        author: String = "Ann Author <ann@example.invalid>",
    ): String {
        for ((path, text) in files) {
            File(work, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        clock += 3600
        val dates = mapOf("GIT_AUTHOR_DATE" to "@$clock +0530", "GIT_COMMITTER_DATE" to "@$clock +0000")
        git.run(work, "add", "-A")
        git.run(work, dates, "commit", "-q", "--author", author, "-m", message)

        return hash("HEAD")
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    private fun onto() = OntoBase(MAIN, hash(MAIN))

    private suspend fun squashOnto(message: String = "Add a parser with a cache") = rewriteBranch(
        gitDir,
        BranchRewrite(FEAT, hash(FEAT), listOf(RewriteGroup(feature, message)), RewriteKind.SQUASH, onto()),
    )

    private fun line() = BranchLine(
        branch = FEAT,
        base = MAIN,
        commits = feature.reversed().map { hash ->
            Commit(hash, "", Identity("A", "a@example.invalid"), Identity("A", "a@example.invalid"), 0, emptyList())
        },
    )

    private fun <T> Either<T, GitError>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> fail("Expected it to work, but got $error")
    }

    private fun Either<*, GitError>.error(): GitError = when (this) {
        is Either.Err -> error
        is Either.Ok -> fail("Expected an error, but got $value")
    }

    private fun status(dir: File = work) = git.run(dir, "status", "--porcelain=v1", "--untracked-files=all")

    @Test
    fun `the one commit goes on main with the tree that git rebase main ends with`(): Unit = runBlocking {
        val twin = File(root, "twin").also { work.copyRecursively(it) }
        git.run(twin, "rebase", "-q", "main")

        val result = squashOnto().ok()

        assertEquals(hash(MAIN), hash("feat~1"))
        assertEquals(hash("feat^{tree}", twin), hash("feat^{tree}"))
        assertEquals(
            "Bea Writer <bea@example.invalid>\nAdd a parser with a cache\n",
            git.run(work, "log", "-1", "--format=%an <%ae>%n%B", FEAT).trimEnd() + "\n",
        )
        assertEquals(OntoBase(MAIN, hash(MAIN)), result.onto)
        assertEquals(feature.map { it to result.newTip }, result.rewritten)
        // The working tree follows: main's files are there, and nothing is left changed
        assertEquals("", status())
        assertEquals("one, changed\ntwo\nthree, cached\n", File(work, "shared.txt").readText())
        assertEquals("notes\n", File(work, "notes.txt").readText())
        assertEquals("squash onto main: Add a parser with a cache", git.run(work, "reflog", "-1", "--format=%gs", "HEAD").trim())
        assertEquals("squash onto main: Add a parser with a cache", git.run(work, "reflog", "-1", "--format=%gs", "feat").trim())
    }

    @Test
    fun `uncommitted changes that main's files don't meet stay, staged ones staged`(): Unit = runBlocking {
        File(work, "b.txt").writeText("b, staged\n")
        git.run(work, "add", "b.txt")
        File(work, "c.txt").writeText("c, changed\n")
        File(work, "untracked.txt").writeText("mine\n")
        val staged = git.run(work, "diff", "--cached")
        val unstaged = git.run(work, "diff")

        squashOnto().ok()

        assertEquals("M  b.txt\n M c.txt\n?? untracked.txt\n", status())
        assertEquals(staged, git.run(work, "diff", "--cached"))
        assertEquals(unstaged, git.run(work, "diff"))
    }

    @Test
    fun `a change to a file that main changes is in the way, and nothing changes`(): Unit = runBlocking {
        File(work, "shared.txt").appendText("four, mine\n")
        val tip = hash(FEAT)
        val before = status()

        val error = squashOnto().error()

        assertEquals("feat", (error as RewriteBranchError.ChangesInTheWay).branch)
        assertEquals(listOf("shared.txt"), error.files)
        assertEquals(tip, hash(FEAT))
        assertEquals(before, status())
    }

    @Test
    fun `an untracked or an ignored file where main adds one is in the way`(): Unit = runBlocking {
        val tip = hash(FEAT)
        File(work, "notes.txt").writeText("my notes\n")

        assertEquals(listOf("notes.txt"), (squashOnto().error() as RewriteBranchError.ChangesInTheWay).files)

        // git's read-tree would overwrite an ignored file without a word
        File(work, ".git/info/exclude").writeText("notes.txt\n")
        assertEquals(listOf("notes.txt"), (squashOnto().error() as RewriteBranchError.ChangesInTheWay).files)

        assertEquals("my notes\n", File(work, "notes.txt").readText())
        assertEquals(tip, hash(FEAT))
    }

    @Test
    fun `conflicts with main are refused, and nothing changes`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "main")
        commit("Change the cached line too", "shared.txt" to "one, changed\ntwo\nthree, on main\n")
        git.run(work, "switch", "-q", "feat")
        val tip = hash(FEAT)

        assertEquals(
            RewriteBranchError.ConflictsWithBase("feat", "main", listOf("shared.txt")),
            squashOnto().error(),
        )
        assertEquals(tip, hash(FEAT))
        assertEquals("", status())
    }

    @Test
    fun `a branch checked out in another worktree takes main's files there`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "main")
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "feat")
        File(linked, "c.txt").writeText("c, changed\n")

        val result = squashOnto().ok()

        assertEquals(result.newTip, hash("HEAD", linked))
        assertEquals(" M c.txt\n", status(linked))
        assertEquals("notes\n", File(linked, "notes.txt").readText())
        // The tab's own worktree, on main, didn't change
        assertEquals(hash(MAIN), hash("HEAD"))
        assertEquals("", status())
    }

    @Test
    fun `a branch that no worktree has checked out moves alone`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "main")
        File(work, "shared.txt").appendText("mine, on main\n")
        val before = status()

        val result = squashOnto().ok()

        assertEquals(result.newTip, hash(FEAT))
        assertEquals(hash(MAIN), hash("$FEAT~1"))
        assertEquals(before, status())
    }

    @Test
    fun `undo puts the branch and its files back, and keeps the uncommitted changes`(): Unit = runBlocking {
        val tip = hash(FEAT)
        File(work, "c.txt").writeText("c, changed\n")
        val result = squashOnto().ok()

        assertEquals(Either.Ok(Unit), undoRewrite(gitDir, result))

        assertEquals(tip, hash(FEAT))
        assertEquals(" M c.txt\n", status())
        assertEquals("one\ntwo\nthree, cached\n", File(work, "shared.txt").readText())
        assertTrue(!File(work, "notes.txt").exists())
        assertEquals("undo squash: back to ${tip.take(7)}", git.run(work, "reflog", "-1", "--format=%gs", "HEAD").trim())
    }

    @Test
    fun `undo refuses while a change is in the way of going back, and changes nothing`(): Unit = runBlocking {
        val result = squashOnto().ok()
        File(work, "notes.txt").writeText("notes, edited after\n")

        val error = undoRewrite(gitDir, result).error()

        assertEquals(listOf("notes.txt"), (error as RewriteBranchError.ChangesInTheWay).files)
        assertEquals(result.newTip, hash(FEAT))
        assertEquals("notes, edited after\n", File(work, "notes.txt").readText())
    }

    @Test
    fun `only the whole branch goes onto main, and nothing changes otherwise`(): Unit = runBlocking {
        val tip = hash(FEAT)
        val part = BranchRewrite(FEAT, tip, listOf(RewriteGroup(feature.drop(1), "Part")), RewriteKind.SQUASH, onto())

        assertTrue(rewriteBranch(gitDir, part) is Either.Err)
        assertEquals(tip, hash(FEAT))
        assertEquals("", status())
    }

    // The dialog's check

    @Test
    fun `the check counts main's new commits, and finds nothing in the way`(): Unit = runBlocking {
        File(work, "c.txt").writeText("c, changed\n")

        assertEquals(OntoBaseCheck.Clean(onto(), newCommits = 2), checkOnto(gitDir, line()).ok())
    }

    @Test
    fun `the check names what conflicts, and what is in the way`(): Unit = runBlocking {
        File(work, "shared.txt").appendText("four, mine\n")
        File(work, "notes.txt").writeText("my notes\n")

        assertEquals(
            OntoBaseCheck.ChangesInTheWay(onto(), 2, work.canonicalFile.path, listOf("notes.txt", "shared.txt")),
            checkOnto(gitDir, line()).ok().let {
                // The worktree's path as the check gives it, which may not be canonical
                (it as OntoBaseCheck.ChangesInTheWay).copy(worktreePath = File(it.worktreePath).canonicalPath)
            },
        )

        git.run(work, "checkout", "-q", "--", "shared.txt")
        File(work, "notes.txt").delete()
        git.run(work, "switch", "-q", "main")
        commit("Change the cached line too", "shared.txt" to "one, changed\ntwo\nthree, on main\n")
        git.run(work, "switch", "-q", "feat")

        assertEquals(OntoBaseCheck.Conflicts(onto(), 3, listOf("shared.txt")), checkOnto(gitDir, line()).ok())
    }

    @Test
    fun `the check has nothing to offer for a branch that has main's tip`(): Unit = runBlocking {
        git.run(work, "merge", "-q", "--no-edit", "main")

        assertNull(checkOnto(gitDir, line()).ok())
    }

    @Test
    fun `the check of a branch that isn't checked out doesn't look at the tab's changes`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "main")
        File(work, "shared.txt").appendText("mine, on main\n")

        assertEquals(OntoBaseCheck.Clean(onto(), newCommits = 2), checkOnto(gitDir, line()).ok())
    }
}
