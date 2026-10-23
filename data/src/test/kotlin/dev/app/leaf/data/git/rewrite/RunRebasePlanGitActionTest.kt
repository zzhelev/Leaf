// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanAction.DROP
import dev.app.leaf.domain.rewrite.PlanAction.EDIT
import dev.app.leaf.domain.rewrite.PlanAction.FIXUP
import dev.app.leaf.domain.rewrite.PlanAction.PICK
import dev.app.leaf.domain.rewrite.PlanAction.REWORD
import dev.app.leaf.domain.rewrite.PlanAction.SQUASH
import dev.app.leaf.domain.rewrite.PlanRow
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RewriteKind
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** When every commit is made, in both repositories, so that the same commit has the same id in both. */
private const val COMMITTER_DATE = "@1800000000 +0000"

private const val FEAT = "refs/heads/feat"
private const val MAIN = "refs/heads/main"

/**
 * Running a commit editor's plan that doesn't stop (fork-only, B3), against git: a plan that reorders, drops, joins,
 * rewords and moves onto main gives, commit for commit, what `git rebase -i` gives for the same todo in a copy of the
 * repository, with the same committer date, and `post-rewrite` gets the same list. Then where the branch's files go,
 * what stops it, merges, a group whose first commit comes out empty, and Undo.
 */
@DisabledOnOs(OS.WINDOWS)
class RunRebasePlanGitActionTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val remote by lazy {
        TestRemoteCommand(
            helper = checkNotNull(builtAskpassHelper()),
            globalConfig = globalConfig,
            shellVariables = mapOf("GIT_COMMITTER_DATE" to COMMITTER_DATE),
            jgit = jgit,
        )
    }
    private val runPlan by lazy {
        RunRebasePlanGitAction(
            jgit,
            remote.gitCli,
            remote.command,
            remote.stateRepository,
            CheckRebasePlanGitAction(jgit, remote.gitCli),
        )
    }
    private val undoRewrite by lazy { UndoBranchRewriteGitAction(jgit, remote.gitCli) }

    private lateinit var work: File
    private var clock = 1_700_000_000L
    private val someone = Identity("Leaf Test", "test@example.invalid")

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        assumeTrue(builtAskpassHelper() != null, "Leaf's askpass helper isn't built")
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = File(root, "work")
        git.run(root, "init", "-q", work.path)
        commit(work, "Base", "a.txt" to "a\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    /** Commits every change in [dir], an hour after the last commit, with an author time zone other than UTC. */
    private fun commit(
        dir: File,
        message: String,
        vararg files: Pair<String, String>,
        author: String = "Ann Author <ann@example.invalid>",
    ): String {
        for ((path, text) in files) {
            File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        clock += 3600
        val dates = mapOf("GIT_AUTHOR_DATE" to "@$clock +0530", "GIT_COMMITTER_DATE" to "@$clock +0000")
        git.run(dir, "add", "-A")
        git.run(dir, dates, "commit", "-q", "--allow-empty", "--author", author, "-m", message)

        return hash("HEAD", dir)
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    /** feat, checked out: Base, then A (Bea's), B, C and D (Ann's), each with its own file. */
    private fun feature(): List<String> {
        git.run(work, "switch", "-q", "-c", "feat")

        return listOf(
            commit(work, "Add the parser\n\nReads the files.", "b.txt" to "b\n", author = "Bea Writer <bea@example.invalid>"),
            commit(work, "wip", "c.txt" to "c\n"),
            commit(work, "Cache the parsed files", "d.txt" to "d\n"),
            commit(work, "Test the cache", "e.txt" to "e\n"),
        )
    }

    private fun commitOf(hash: String): Commit {
        val (id, parents, message) = git.run(work, "log", "-1", "--format=%H%x00%P%x00%B", hash).split('\u0000', limit = 3)

        return Commit(id, message, someone, someone, 0, parents.split(' ').filter { it.isNotBlank() })
    }

    /** A plan of feat's own commits since [since], with [rows] (oldest first) and [messages], onto [onto]. */
    private fun plan(
        vararg rows: Pair<String, PlanAction>,
        messages: Map<String, String> = emptyMap(),
        onto: String? = null,
        since: String = "main",
    ): RebasePlan {
        val hashes = git.run(work, "rev-list", "--reverse", "--first-parent", "$since..feat").lines().filter { it.isNotBlank() }
        val original = hashes.map(::commitOf)
        val line = BranchLine(FEAT, MAIN, original.reversed(), upstream = null)

        return RebasePlan(
            line = line,
            original = original,
            rows = rows.map { (hash, action) -> PlanRow(original.first { it.hash == hash }, action) },
            messages = messages,
            onto = onto?.let { OntoBase(MAIN, it) },
        )
    }

    /** A copy of [work], where git rebases the same commits. */
    private fun twin(): File = File(root, "twin").also { work.copyRecursively(it) }

    /**
     * Runs `git rebase -i` in [dir] with [todo], onto [onto] from [upstream], as Leaf runs a plan that stops (B4), and
     * [message] for the commit that its editor opens on, with the same committer date as Leaf's commits.
     */
    private fun rebaseLikeGit(dir: File, onto: String, upstream: String, todo: List<String>, message: String? = null) {
        val scripts = File(root, "scripts").apply { mkdirs() }
        val todoFile = File(scripts, "todo").apply { writeText(todo.joinToString("\n", postfix = "\n")) }
        val messageFile = File(scripts, "message").apply { writeText(message.orEmpty()) }
        val sequenceEditor = File(scripts, "sequence-editor").writeExecutable("#!/bin/sh\ncat '$todoFile' > \"\$1\"\n")
        val editor = File(scripts, "editor").writeExecutable("#!/bin/sh\ncat '$messageFile' > \"\$1\"\n")
        val environment = mapOf(
            "GIT_SEQUENCE_EDITOR" to sequenceEditor.path,
            "GIT_EDITOR" to editor.path,
            "GIT_COMMITTER_DATE" to COMMITTER_DATE,
        )

        git.run(
            dir, environment, "rebase", "-q", "-i", "--empty=drop", "--reapply-cherry-picks", "--no-autosquash",
            "--no-update-refs", "--onto", onto, upstream, "feat",
        )
    }

    /** Every commit of [ref], with its id, tree, parents, author, committer and message. */
    private fun rawLog(dir: File, ref: String = FEAT) = git.run(dir, "log", "--format=raw", ref)

    private fun <T> Either<T, GitError>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> fail("Expected it to work, but got $error")
    }

    private fun Either<*, GitError>.error(): GitError = when (this) {
        is Either.Err -> error
        is Either.Ok -> fail("Expected an error, but got $value")
    }

    // The same commits as git rebase -i

    @Test
    fun `a plan that reorders, drops and rewords gives what git rebase -i gives, and keeps the rows that stay`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val twin = twin()
        val message = "Cache the parsed files\n\n# A comment that git leaves out\nKeeps them by path."

        val result = runPlan(gitDir, plan(a to PICK, d to PICK, c to REWORD, b to DROP, messages = mapOf(c to message))).ok()
        rebaseLikeGit(twin, a, a, listOf("pick $d", "reword $c", "drop $b"), message)

        assertEquals(rawLog(twin), rawLog(work))
        assertEquals(a, hash("feat~2"))
        assertEquals(RewriteKind.REBASE, result.kind)
        assertEquals(listOf(d to hash("feat~1"), c to hash(FEAT)), result.rewritten)
        // The working tree follows: B's file is gone with B
        assertFalse(File(work, "c.txt").exists())
        assertEquals("", git.run(work, "status", "--porcelain=v1"))
    }

    @Test
    fun `a plan that joins commits and moves onto main gives git's commits, with the first one's author`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        val main = commit(work, "Main moves on", "m.txt" to "m\n")
        git.run(work, "switch", "-q", "feat")
        val twin = twin()
        val message = "Add the parser, then a cache"

        runPlan(
            gitDir,
            plan(a to PICK, b to SQUASH, c to FIXUP, d to PICK, messages = mapOf(a to message), onto = main, since = "main~1"),
        ).ok()
        rebaseLikeGit(twin, main, hash("main~1"), listOf("pick $a", "squash $b", "fixup $c", "pick $d"), message)

        assertEquals(rawLog(twin), rawLog(work))
        assertEquals("Bea Writer", git.run(work, "log", "-1", "--format=%an", "feat~1").trim())
        assertEquals("rebase onto main: $message", git.run(work, "reflog", "-1", "--format=%gs", "feat").trim())
        assertEquals("m\n", File(work, "m.txt").readText())
    }

    @Test
    fun `a commit whose changes main has is left out, as git's --empty=drop leaves it out`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        val main = commit(work, "wip on main too", "c.txt" to "c\n")
        git.run(work, "switch", "-q", "feat")
        val twin = twin()

        val result = runPlan(gitDir, plan(a to PICK, b to PICK, c to PICK, d to PICK, onto = main, since = "main~1")).ok()
        rebaseLikeGit(twin, main, hash("main~1"), listOf("pick $a", "pick $b", "pick $c", "pick $d"))

        assertEquals(rawLog(twin), rawLog(work))
        assertEquals(listOf(a, c, d), result.rewritten.map { it.first })
    }

    @Test
    fun `post-rewrite gets what git rebase gives it`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val out = "../\$(basename \"\$PWD\").post-rewrite"
        val twin = twin()

        for (dir in listOf(work, twin)) {
            File(dir, ".git/hooks/post-rewrite").writeExecutable("#!/bin/sh\necho \"\$*\" > $out\ncat >> $out\n")
        }

        runPlan(gitDir, plan(a to PICK, c to PICK, b to PICK, d to FIXUP)).ok()
        rebaseLikeGit(twin, a, a, listOf("pick $c", "pick $b", "fixup $d"))

        val leaf = File(root, "work.post-rewrite").readText()
        assertEquals("rebase\n$c ${hash("feat~1")}\n$b ${hash(FEAT)}\n$d ${hash(FEAT)}\n", leaf)
        assertEquals(File(root, "twin.post-rewrite").readText(), leaf)
    }

    // The files

    @Test
    fun `uncommitted changes that the plan doesn't touch stay, staged ones staged`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        File(work, "a.txt").writeText("a, staged\n")
        git.run(work, "add", "a.txt")
        File(work, "e.txt").writeText("e, changed\n")

        runPlan(gitDir, plan(a to PICK, b to DROP, c to PICK, d to PICK)).ok()

        assertEquals("M  a.txt\n M e.txt\n", git.run(work, "status", "--porcelain=v1"))
        assertFalse(File(work, "c.txt").exists())
    }

    @Test
    fun `an uncommitted change in a file that the plan changes is in the way, and nothing moves`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        File(work, "c.txt").writeText("c, changed\n")

        val error = runPlan(gitDir, plan(a to PICK, b to DROP, c to PICK, d to PICK)).error()

        assertEquals(RewriteBranchError.ChangesInTheWay("feat", work.canonicalFile.path, listOf("c.txt"), ""), error.withCanonicalPath())
        assertEquals(d, hash(FEAT))
        assertEquals("c, changed\n", File(work, "c.txt").readText())
    }

    private fun GitError.withCanonicalPath(): GitError =
        if (this is RewriteBranchError.ChangesInTheWay) copy(worktreePath = File(worktreePath).canonicalFile.path) else this

    @Test
    fun `a branch checked out in another worktree moves there with its files, and the tab's stays`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "feat")
        val ownHead = hash("HEAD")

        val result = runPlan(gitDir, plan(a to PICK, b to DROP, c to PICK, d to PICK)).ok()

        assertEquals(result.newTip, hash("HEAD", linked))
        assertFalse(File(linked, "c.txt").exists())
        assertEquals("", git.run(linked, "status", "--porcelain=v1"))
        assertEquals(ownHead, hash("HEAD"))
    }

    // What stops it, changing nothing

    @Test
    fun `a plan that stops is refused, with where`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "Add the cache", "cache.txt" to "1\n2\n3\n")
        val b = commit(work, "Fix the cache", "cache.txt" to "1\nfixed\n3\n")

        assertEquals(
            RewriteBranchError.PlanStops("feat", b, listOf("cache.txt")),
            runPlan(gitDir, plan(a to DROP, b to PICK)).error(),
        )
        assertEquals(RewriteBranchError.PlanStops("feat", a, emptyList()), runPlan(gitDir, plan(a to EDIT, b to PICK)).error())
        assertEquals(b, hash(FEAT))
    }

    @Test
    fun `a branch that moved since the plan was made is refused`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val stale = plan(a to PICK, b to DROP, c to PICK, d to PICK)
        val moved = commit(work, "An agent's commit", "f.txt" to "f\n")

        assertEquals(RewriteBranchError.BranchMoved("feat"), runPlan(gitDir, stale).error())
        assertEquals(moved, hash(FEAT))
    }

    @Test
    fun `a merge kept as it is is made again with its other parent, and one the plan replays is refused`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "side")
        val side = commit(work, "On the side", "s.txt" to "s\n")
        git.run(work, "switch", "-q", "main")
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "b.txt" to "b\n")
        val b = commit(work, "B", "c.txt" to "c\n")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = hash("HEAD")
        val d = commit(work, "D", "d.txt" to "d\n")

        assertEquals(
            RewriteBranchError.ReplaysMerge(merge),
            runPlan(gitDir, plan(a to DROP, b to PICK, merge to PICK, d to PICK)).error(),
        )

        runPlan(gitDir, plan(a to REWORD, b to PICK, merge to PICK, d to PICK, messages = mapOf(a to "A, reworded"))).ok()

        val newMerge = hash("feat~1")
        assertEquals("${hash("feat~2")} $side", git.run(work, "log", "-1", "--format=%P", newMerge).trim())
        assertEquals(hash("$merge^{tree}"), hash("$newMerge^{tree}"))
        assertEquals(hash("$d^{tree}"), hash("feat^{tree}"))
    }

    @Test
    fun `a merge that brings a changed commit in again is refused`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "-c", "side", b)
        commit(work, "On a branch from B", "s.txt" to "s\n")
        git.run(work, "switch", "-q", "feat")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = hash("HEAD")
        val plan = plan(a to PICK, b to REWORD, c to PICK, d to PICK, merge to PICK, messages = mapOf(b to "Work in progress"))

        assertEquals(RewriteBranchError.MergedAgain("feat", merge), runPlan(gitDir, plan).error())
        assertEquals(merge, hash(FEAT))
    }

    @Test
    fun `a group whose first commit comes out empty keeps the rest, with its message and author`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        val main = commit(work, "wip on main too", "c.txt" to "c\n")
        git.run(work, "switch", "-q", "feat")

        runPlan(gitDir, plan(a to PICK, b to PICK, c to FIXUP, d to PICK, onto = main, since = "main~1")).ok()

        assertEquals("wip", git.run(work, "log", "-1", "--format=%s", "feat~1").trim())
        assertEquals("d.txt\n", git.run(work, "diff-tree", "--no-commit-id", "--name-only", "-r", "feat~1"))
        assertEquals(hash("$d^{tree}"), hash("feat^{tree}"))
        // A, then B with C's changes, then D
        assertEquals("3", git.run(work, "rev-list", "--count", "$main..feat").trim())
    }

    // Undo

    @Test
    fun `undo puts the commits and the files back`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        File(work, "e.txt").writeText("e, changed\n")
        val result = runPlan(gitDir, plan(a to PICK, b to DROP, c to PICK, d to PICK)).ok()

        assertEquals(Either.Ok(Unit), undoRewrite(gitDir, result))

        assertEquals(d, hash(FEAT))
        assertEquals("c\n", File(work, "c.txt").readText())
        assertEquals(" M e.txt\n", git.run(work, "status", "--porcelain=v1"))
        assertEquals("undo rebase: back to ${d.take(7)}", git.run(work, "reflog", "-1", "--format=%gs", "HEAD").trim())
    }
}
