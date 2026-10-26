// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.operations.testGitCliOperations
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
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
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.PlanRecord
import dev.app.leaf.domain.rewrite.PlanRow
import dev.app.leaf.domain.rewrite.RebasePlan
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * Starting a commit editor's plan that stops (fork-only, B4) with `git rebase -i`: it stops where the check says, with
 * the plan's record next to git's files, and the plan's messages are the commits' after a Continue, Leaf's or a
 * terminal's, also where a reworded commit itself conflicts. Then an edit, the user's rebase settings, where the branch
 * is, uncommitted changes, and a group whose first commit comes out empty.
 */
@DisabledOnOs(OS.WINDOWS)
class StartRebasePlanGitActionTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val remote by lazy {
        TestRemoteCommand(helper = checkNotNull(builtAskpassHelper()), globalConfig = globalConfig, jgit = jgit)
    }
    private val startPlan by lazy {
        StartRebasePlanGitAction(
            jgit,
            remote.command,
            testGitCliOperations(jgit, remote.gitCli),
            CheckRebasePlanGitAction(jgit, remote.gitCli),
        )
    }
    private val planRecord by lazy { GetPlanRecordGitAction(jgit) }

    private lateinit var work: File
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
        commit("Base", "a.txt" to "a\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun commit(message: String, vararg files: Pair<String, String>, dir: File = work): String {
        for ((path, text) in files) {
            File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        git.run(dir, "add", "-A")
        git.run(dir, "commit", "-q", "--allow-empty", "-m", message)

        return hash("HEAD", dir)
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    private fun subject(rev: String, dir: File = work) = git.run(dir, "log", "-1", "--format=%s", rev).trim()

    /** feat, checked out: A adds the cache, B changes it, C and D add files of their own. */
    private fun feature(): List<String> {
        git.run(work, "switch", "-q", "-c", "feat")

        return listOf(
            commit("Add the cache", "cache.txt" to "1\n2\n3\n"),
            commit("Fix the cache", "cache.txt" to "1\nfixed\n3\n"),
            commit("Add the parser", "parser.txt" to "p\n"),
            commit("Test the parser", "test.txt" to "t\n"),
        )
    }

    private fun commitOf(hash: String): Commit {
        val (id, parents, message) = git.run(work, "log", "-1", "--format=%H%x00%P%x00%B", hash).split('\u0000', limit = 3)

        return Commit(id, message, someone, someone, 0, parents.split(' ').filter { it.isNotBlank() })
    }

    private fun line(since: String = "main"): Pair<BranchLine, List<Commit>> {
        val hashes = git.run(work, "rev-list", "--reverse", "--first-parent", "$since..feat").lines().filter { it.isNotBlank() }
        val original = hashes.map(::commitOf)

        return BranchLine(FEAT, MAIN, original.reversed()) to original
    }

    private fun plan(
        vararg rows: Pair<String, PlanAction>,
        messages: Map<String, String> = emptyMap(),
        onto: String? = null,
        since: String = "main",
    ): RebasePlan {
        val (line, original) = line(since)

        return RebasePlan(
            line = line,
            original = original,
            rows = rows.map { (hash, action) -> PlanRow(original.first { it.hash == hash }, action) },
            messages = messages,
            onto = onto?.let { OntoBase(MAIN, it) },
        )
    }

    private fun <T> Either<T, GitError>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> fail("Expected it to work, but got $error")
    }

    /** Resolves the conflict in cache.txt by keeping it as the stopped commit has it. */
    private fun resolveKeepingTheirs(dir: File = work) {
        git.run(dir, "checkout", "--theirs", "cache.txt")
        git.run(dir, "add", "cache.txt")
    }

    private fun continueLikeLeaf(dir: File = work) = git.run(dir, mapOf("GIT_EDITOR" to ":"), "rebase", "--continue")

    // Where it stops

    @Test
    fun `a plan that conflicts stops at that commit, and its record gives the plan back after an abort`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val plan = plan(a to DROP, b to PICK, c to PICK, d to PICK)

        val outcome = startPlan(gitDir, plan, autostash = false).ok()

        // In the tab's worktree, where feat is checked out
        assertEquals(File(work, ".git").canonicalPath, File((outcome as PlanRebaseOutcome.Stopped).gitDir).canonicalPath)
        assertEquals(work.canonicalPath, File(outcome.worktreePath).canonicalPath)
        assertTrue(outcome.atConflicts)
        assertEquals(b, hash("REBASE_HEAD"))
        assertEquals("cache.txt\n", git.run(work, "diff", "--name-only", "--diff-filter=U"))

        val record = planRecord(gitDir).ok()!!
        git.run(work, "rebase", "--abort")

        assertEquals(d, hash(FEAT))
        assertEquals(plan, record.planOn(line().first))
        assertNull(planRecord(gitDir).ok())
    }

    @Test
    fun `a new message is the plan's after the stop, Leaf's Continue or a terminal's`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val subjects = { git.run(work, "log", "--format=%s", "main..feat").lines().filter { it.isNotBlank() } }
        val message = "Parse config files\n\n# A comment that git leaves out\nWith a cache."
        val plan = plan(a to DROP, b to PICK, c to REWORD, d to PICK, messages = mapOf(c to message))

        startPlan(gitDir, plan, autostash = false).ok()
        resolveKeepingTheirs()
        continueLikeLeaf()

        assertEquals("Parse config files\n\nWith a cache.", git.run(work, "log", "-1", "--format=%B", "feat~1").trim())

        assertEquals(listOf("Test the parser", "Parse config files", "Fix the cache"), subjects())
        assertNull(planRecord(gitDir).ok())
        assertFalse(File(work, ".git/rebase-merge").exists())
    }

    @Test
    fun `a reworded commit that conflicts itself keeps the plan's message, and its record names the commit`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val plan = plan(a to DROP, b to REWORD, c to PICK, d to PICK, messages = mapOf(b to "Fix the cache's second line"))

        startPlan(gitDir, plan, autostash = false).ok()

        val stoppedAt = hash("REBASE_HEAD")
        assertEquals(b, planRecord(gitDir).ok()!!.originalOf(stoppedAt))
        assertEquals("Fix the cache's second line", subject(stoppedAt))

        // A terminal's continue opens the user's editor on the planned message; one that keeps it gives the same
        resolveKeepingTheirs()
        git.run(work, mapOf("GIT_EDITOR" to "true"), "rebase", "--continue")

        assertEquals("Fix the cache's second line", subject("feat~2"))
        assertEquals(listOf(subject(c), subject(d)), listOf(subject("feat~1"), subject(FEAT)))
    }

    @Test
    fun `an edit stops after its commit, and the rest follows once it continues`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val unchanged = listOf(a, b)

        val outcome = startPlan(gitDir, plan(a to PICK, b to PICK, c to EDIT, d to PICK), autostash = false).ok()

        assertInstanceOf(PlanRebaseOutcome.Stopped::class.java, outcome)
        assertFalse((outcome as PlanRebaseOutcome.Stopped).atConflicts)
        assertTrue(File(work, ".git/rebase-merge/amend").exists())
        assertEquals("Add the parser", subject("HEAD"))

        continueLikeLeaf()

        assertEquals(hash("$d^{tree}"), hash("feat^{tree}"))
        // The rows before the edit stay as they were
        assertEquals(unchanged, listOf(hash("feat~3"), hash("feat~2")))
    }

    // The user's settings, and a plan that runs through

    @Test
    fun `the user's rebase settings don't change the plan, and a plan that runs through is done`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "branch", "stacked", c)
        git.run(work, "config", "rebase.missingCommitsCheck", "error")
        git.run(work, "config", "rebase.updateRefs", "true")
        git.run(work, "config", "rebase.autoSquash", "true")
        git.run(work, "config", "rebase.autoStash", "true")
        val plan = plan(a to PICK, b to PICK, d to PICK, c to REWORD, messages = mapOf(c to "Add the parser, at last"))

        val outcome = startPlan(gitDir, plan, autostash = false).ok()

        assertInstanceOf(PlanRebaseOutcome.Done::class.java, outcome)
        val result = (outcome as PlanRebaseOutcome.Done).result
        assertEquals(d, result.oldTip)
        assertEquals(hash(FEAT), result.newTip)
        val subjects = git.run(work, "log", "--format=%s", "-3", "feat").lines().filter { it.isNotBlank() }

        assertEquals(listOf("Add the parser, at last", "Test the parser", "Fix the cache"), subjects)
        // The branch on C is left alone, as git's --update-refs would have moved it
        assertEquals(c, hash("stacked"))
    }

    // Where the branch is

    @Test
    fun `a branch that no worktree has checked out is checked out here first`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")

        startPlan(gitDir, plan(a to DROP, b to PICK, c to PICK, d to PICK), autostash = false).ok()
        git.run(work, "rebase", "--abort")

        assertEquals("refs/heads/feat", git.run(work, "symbolic-ref", "HEAD").trim())
        assertEquals(d, hash(FEAT))
    }

    @Test
    fun `a branch checked out in another worktree rebases there, and the tab's worktree stays`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "feat")
        val ownHead = hash("HEAD")

        val outcome = startPlan(gitDir, plan(a to DROP, b to PICK, c to PICK, d to PICK), autostash = false).ok()

        val linkedGitDir = File(git.run(linked, "rev-parse", "--absolute-git-dir").trim())
        assertEquals(linkedGitDir.canonicalPath, File((outcome as PlanRebaseOutcome.Stopped).gitDir).canonicalPath)
        assertEquals(linked.canonicalPath, File(outcome.worktreePath).canonicalPath)
        assertEquals(b, hash("REBASE_HEAD", linked))
        assertEquals(ownHead, hash("HEAD"))
        assertFalse(File(work, ".git/rebase-merge").exists())
        assertTrue(planRecord(linkedGitDir.path).ok() != null)
    }

    @Test
    fun `uncommitted changes are set aside with autostash and come back, and git refuses them without`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        File(work, "a.txt").writeText("a, changed\n")
        // The user's setting doesn't set them aside: the dialog's box does
        git.run(work, "config", "rebase.autoStash", "true")
        val plan = plan(a to DROP, b to PICK, c to PICK, d to PICK)

        val refused = startPlan(gitDir, plan, autostash = false)

        assertInstanceOf(GitCliError.CommandFailed::class.java, (refused as Either.Err).error)
        assertEquals(d, hash(FEAT))
        assertFalse(File(work, ".git/rebase-merge").exists())

        startPlan(gitDir, plan, autostash = true).ok()
        assertEquals("a\n", File(work, "a.txt").readText())
        git.run(work, "rebase", "--abort")

        assertEquals("a, changed\n", File(work, "a.txt").readText())
        assertEquals(d, hash(FEAT))
    }

    // Empty commits

    @Test
    fun `a group whose first commit came out empty starts from the next one, with its message`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit("Add the parser", "parser.txt" to "p\n")
        val b = commit("wip", "wip.txt" to "w\n")
        val c = commit("fixup! wip", "more.txt" to "m\n")
        val d = commit("Test the parser", "test.txt" to "t\n")
        git.run(work, "switch", "-q", "main")
        val main = commit("wip on main too", "wip.txt" to "w\n")
        git.run(work, "switch", "-q", "feat")
        val plan = plan(a to PICK, b to PICK, c to FIXUP, d to EDIT, onto = main, since = "main~1")

        startPlan(gitDir, plan, autostash = false).ok()
        continueLikeLeaf()

        assertEquals("wip", subject("feat~1"))
        assertEquals("more.txt\n", git.run(work, "diff-tree", "--no-commit-id", "--name-only", "-r", "feat~1"))
        assertEquals("Add the parser", subject("feat~2"))
        assertEquals(main, hash("feat~3"))
    }

    @Test
    fun `a commit that comes out empty after the stop, where the check can't see, is left out`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val plan = { main: String -> plan(a to DROP, b to PICK, c to PICK, d to PICK, onto = main, since = "main~1") }
        git.run(work, "switch", "-q", "main")
        val main = commit("Add the parser on main too", "parser.txt" to "p\n")
        git.run(work, "switch", "-q", "feat")

        startPlan(gitDir, plan(main), autostash = false).ok()
        resolveKeepingTheirs()
        continueLikeLeaf()

        // Add the parser came out empty on main, which has its file
        val subjects = git.run(work, "log", "--format=%s", "$main..feat").lines().filter { it.isNotBlank() }

        assertEquals(listOf("Test the parser", "Fix the cache"), subjects)
        assertFalse(File(work, ".git/rebase-merge").exists())
    }

    @Test
    fun `the record says which plan is waiting, and names nothing when no rebase waits`(): Unit = runBlocking {
        val (a, b, c, d) = feature()

        assertNull(planRecord(gitDir).ok())

        startPlan(gitDir, plan(a to DROP, b to PICK, c to PICK, d to PICK), autostash = false).ok()
        val record = planRecord(gitDir).ok()!!

        assertEquals(FEAT, record.branch)
        assertEquals(d, record.expectedTip)
        assertEquals(PlanRecord.parse(File(work, ".git/rebase-merge/leaf-plan").readText()), record)
    }
}
