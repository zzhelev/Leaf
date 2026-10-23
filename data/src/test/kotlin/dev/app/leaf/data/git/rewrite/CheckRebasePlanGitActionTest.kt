// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanRow
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.UnknownStopReason
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

private const val FEAT = "refs/heads/feat"
private const val MAIN = "refs/heads/main"

/**
 * Leaf's check of a commit editor's plan (fork-only, B2), against git: where `git rebase -i` stops with the same todo,
 * the check stops at the same commit with the same files, and where git runs through, each commit it makes has the tree
 * that the check gives its group. Random plans on a generated branch, then the cases one by one: a plan that merges
 * nothing, a move onto a base that renamed a file, commits that come out empty, an edit, a branch from the first
 * commit, a missing object, and the merges that the check remembers.
 */
@DisabledOnOs(OS.WINDOWS)
class CheckRebasePlanGitActionTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val gitLog by lazy { File(root, "git.log") }

    /** git as the check runs it, through a wrapper that notes every command, to count the merges. */
    private val gitCli by lazy {
        val wrapper = File(root, "bin/git").apply { parentFile.mkdirs() }
            .writeExecutable("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${gitLog.path}'\nexec git \"\$@\"\n")
        testGitCli(
            mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"),
            configuredPath = wrapper.path,
        )
    }
    private val checkPlan by lazy { CheckRebasePlanGitAction(jgit, gitCli) }

    private lateinit var work: File
    private val someone = Identity("Leaf Test", "test@example.invalid")

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = File(root, "work")
        git.run(root, "init", "-q", work.path)
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun commit(dir: File, message: String, vararg files: Pair<String, String>): String {
        for ((path, text) in files) {
            File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        git.run(dir, "add", "-A")
        git.run(dir, "commit", "-q", "--allow-empty", "-m", message)

        return hash("HEAD", dir)
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    private fun tree(rev: String, dir: File = work) = hash("$rev^{tree}", dir)

    private fun commitOf(hash: String): Commit {
        val (id, parents, message) = git.run(work, "log", "-1", "--format=%H%x00%P%x00%B", hash).split('\u0000', limit = 3)

        return Commit(id, message, someone, someone, 0, parents.split(' ').filter { it.isNotBlank() })
    }

    /** feat's own commits, oldest first, as the menus read them. */
    private fun featLine(base: String? = MAIN, since: String = "main"): Pair<BranchLine, List<Commit>> {
        val hashes = git.run(work, "rev-list", "--reverse", "--first-parent", if (base == null) "feat" else "$since..feat")
            .lines().filter { it.isNotBlank() }
        val commits = hashes.map(::commitOf)

        return BranchLine(FEAT, base, commits.reversed()) to commits
    }

    private fun lines(count: Int, change: Map<Int, String> = emptyMap()) =
        (1..count).joinToString("\n", postfix = "\n") { change[it] ?: "line $it" }

    private suspend fun check(plan: RebasePlan): PlanCheck = when (val result = checkPlan(gitDir, plan)) {
        is Either.Ok -> result.value
        is Either.Err<GitError> -> fail("The check failed: ${result.error}")
    }

    private fun merges() = if (gitLog.exists()) gitLog.readLines().count { "merge-tree" in it } else 0

    private val twin by lazy { File(root, "twin").also { work.copyRecursively(it) } }

    /**
     * What `git rebase -i` does with [todo] in a copy of the repository, onto [onto] from [upstream]. The copy is made
     * once, and put back on feat's commit before each rebase.
     */
    private fun rebaseLikeGit(onto: String, upstream: String, todo: List<String>): GitRebase {
        if (File(twin, ".git/rebase-merge").exists()) git.run(twin, "rebase", "--abort")
        git.run(twin, "checkout", "-q", "-f", "feat")
        git.run(twin, "reset", "-q", "--hard", hash("feat"))

        val scripts = File(root, "scripts").apply { mkdirs() }
        val todoFile = File(scripts, "todo").apply { writeText(todo.joinToString("\n", postfix = "\n")) }
        val sequenceEditor = File(scripts, "sequence-editor").writeExecutable("#!/bin/sh\ncat '$todoFile' > \"\$1\"\n")
        val environment = mapOf("GIT_SEQUENCE_EDITOR" to sequenceEditor.path, "GIT_EDITOR" to "true")
        val stopped = runCatching {
            git.run(
                twin, environment, "rebase", "-q", "-i", "--empty=drop", "--reapply-cherry-picks", "--no-autosquash",
                "--no-update-refs", "--force-rebase", "--onto", onto, upstream, "feat",
            )
        }.isFailure

        if (!stopped) {
            val made = git.run(twin, "rev-list", "--reverse", "$onto..feat").lines().filter { it.isNotBlank() }

            return GitRebase(trees = made.map { tree(it, twin) }, stoppedAt = null, conflicts = emptySet())
        }

        val stoppedAt = hash("REBASE_HEAD", twin)
        val conflicts = git.run(twin, "diff", "--name-only", "--diff-filter=U").lines().filter { it.isNotBlank() }.toSet()

        return GitRebase(trees = emptyList(), stoppedAt = stoppedAt, conflicts = conflicts)
    }

    private data class GitRebase(val trees: List<String>, val stoppedAt: String?, val conflicts: Set<String>)

    /** git's todo for [plan]: what the plan's rows say, a row that joins nothing below it picked. */
    private fun todoOf(plan: RebasePlan): List<String> {
        var anyKept = false

        return plan.rows.map { row ->
            val command = when {
                row.action == PlanAction.DROP -> "drop"
                row.action.joins && anyKept -> if (row.action == PlanAction.SQUASH) "squash" else "fixup"
                else -> "pick"
            }

            if (row.action != PlanAction.DROP) anyKept = true
            "$command ${row.commit.hash}"
        }
    }

    // Random plans, against git rebase -i

    @Test
    fun `random plans stop where git rebase -i stops, with its files, and give its trees where it doesn't`(): Unit = runBlocking {
        val random = Random(73)
        val files = (0 until 6).map { "src/f$it.txt" }
        commit(work, "Base", *files.map { it to lines(30) }.toTypedArray())
        val texts = files.associateWith { mutableMapOf<Int, String>() }
        fun change(prefix: String, n: Int): Array<Pair<String, String>> = (0 until 1 + random.nextInt(2)).map {
            val file = files.random(random)
            texts.getValue(file)[1 + random.nextInt(30)] = "$prefix $n.$it"
            file to lines(30, texts.getValue(file))
        }.toTypedArray()

        git.run(work, "switch", "-q", "-c", "feat")
        repeat(7) { commit(work, "Feature $it", *change("feat", it)) }
        git.run(work, "switch", "-q", "main")
        texts.values.forEach { it.clear() }
        repeat(3) { commit(work, "Main $it", *change("main", it)) }
        git.run(work, "switch", "-q", "feat")

        val (line, original) = featLine()
        val base = hash("main~3")
        val mainTip = hash("main")
        var stops = 0
        var runsThrough = 0

        repeat(40) { round ->
            // Half the plans keep the order, the others swap one or two pairs of commits
            val order = original.toMutableList()

            if (random.nextBoolean()) {
                repeat(1 + random.nextInt(2)) {
                    val (i, j) = order.indices.shuffled(random).take(2)
                    order[i] = order[j].also { order[j] = order[i] }
                }
            }

            val rows = order.map { commit ->
                val action = when (random.nextInt(20)) {
                    0, 1, 2 -> PlanAction.DROP
                    3, 4 -> PlanAction.FIXUP
                    5, 6 -> PlanAction.SQUASH
                    else -> PlanAction.PICK
                }

                PlanRow(commit, action)
            }
            val onto = if (random.nextBoolean()) OntoBase(MAIN, mainTip) else null
            val plan = RebasePlan(line, original, rows, onto = onto)
            val result = check(plan)
            val rebased = rebaseLikeGit(onto?.commit ?: base, base, todoOf(plan))

            when (val stop = result.stop) {
                null -> {
                    runsThrough++
                    assertNull(rebased.stoppedAt, "Round $round: git stopped, the check didn't")
                    assertEquals(rebased.trees, result.groups.filterNot { it.isLeftOut }.map { it.tree }, "Round $round")
                }

                is PlanStop.Conflict -> {
                    stops++
                    assertEquals(rebased.stoppedAt, stop.commit, "Round $round: where it stops")
                    assertEquals(rebased.conflicts, stop.files.toSet(), "Round $round: the files")
                }

                else -> fail("Round $round: $stop")
            }
        }

        // The rounds have both kinds, so that each comparison ran
        assertTrue(stops >= 10 && runsThrough >= 10, "$stops stopped, $runsThrough ran through")
    }

    // One case at a time

    @Test
    fun `a plan that only joins and rewords merges nothing, and keeps the commits' trees`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to "a\n")
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "b.txt" to "b\n")
        val b = commit(work, "B", "c.txt" to "c\n")
        val c = commit(work, "C", "b.txt" to "b2\n")
        val (line, original) = featLine()
        val plan = RebasePlan.of(line, original).withAction(b, PlanAction.FIXUP).withMessage(c, "Change b again")

        val result = check(plan)

        assertNull(result.stop)
        assertEquals(listOf(tree(b), tree(c)), result.groups.map { it.tree })
        assertEquals(listOf(listOf(a, b), listOf(c)), result.groups.map { it.commits })
        assertEquals(0, merges())
    }

    @Test
    fun `a reorder and a drop onto a base that renamed a file give git's trees`(): Unit = runBlocking {
        commit(work, "Base", "old.txt" to lines(20), "a.txt" to lines(20))
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "old.txt" to lines(20, mapOf(3 to "three")))
        val b = commit(work, "B", "a.txt" to lines(20, mapOf(5 to "five")))
        val c = commit(work, "C", "old.txt" to lines(20, mapOf(3 to "three", 15 to "fifteen")))
        val d = commit(work, "D", "n.txt" to "new\n")
        git.run(work, "switch", "-q", "main")
        git.run(work, "mv", "old.txt", "renamed.txt")
        commit(work, "Rename old.txt")
        commit(work, "Main", "a.txt" to lines(20, mapOf(18 to "eighteen")))
        git.run(work, "switch", "-q", "feat")
        val (line, original) = featLine(since = "main~2")
        val plan = RebasePlan(
            line, original,
            listOf(PlanRow(commitOf(b), PlanAction.PICK), PlanRow(commitOf(a), PlanAction.PICK), PlanRow(commitOf(c), PlanAction.DROP), PlanRow(commitOf(d), PlanAction.PICK)),
            onto = OntoBase(MAIN, hash("main")),
        )

        val result = check(plan)
        val rebased = rebaseLikeGit(hash("main"), hash("main~2"), listOf("pick $b", "pick $a", "drop $c", "pick $d"))

        assertNull(result.stop)
        assertEquals(rebased.trees, result.groups.map { it.tree })
        // The rename was followed: A's change is in renamed.txt
        assertEquals(lines(20, mapOf(3 to "three")), git.run(work, "show", "${result.groups[1].tree}:renamed.txt"))
    }

    @Test
    fun `a commit whose changes the base has comes out empty and is left out, as --empty=drop does`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "Two", "a.txt" to lines(10, mapOf(2 to "two")))
        val b = commit(work, "Five", "a.txt" to lines(10, mapOf(2 to "two", 5 to "five")))
        val c = commit(work, "Eight", "a.txt" to lines(10, mapOf(2 to "two", 5 to "five", 8 to "eight")))
        // A commit that changes nothing to start with is kept, as git keeps it
        val nothing = commit(work, "Nothing yet")
        git.run(work, "switch", "-q", "main")
        commit(work, "Five on main", "a.txt" to lines(10, mapOf(5 to "five")))
        git.run(work, "switch", "-q", "feat")
        val (line, original) = featLine(since = "main~1")
        val plan = RebasePlan.of(line, original).copy(onto = OntoBase(MAIN, hash("main")))

        val result = check(plan)
        val rebased = rebaseLikeGit(hash("main"), hash("main~1"), listOf("pick $a", "pick $b", "pick $c", "pick $nothing"))

        assertNull(result.stop)
        assertEquals(setOf(b), result.emptyCommits)
        assertTrue(result.groups[1].isLeftOut)
        assertEquals(4, rebased.trees.size + 1)
        assertEquals(rebased.trees, result.groups.filterNot { it.isLeftOut }.map { it.tree })
    }

    @Test
    fun `a group whose first commit comes out empty keeps its other commits' changes, where git puts them below`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "Two", "a.txt" to lines(10, mapOf(2 to "two")))
        val b = commit(work, "Five", "a.txt" to lines(10, mapOf(2 to "two", 5 to "five")))
        val c = commit(work, "fixup! Five", "a.txt" to lines(10, mapOf(2 to "two", 5 to "five", 8 to "eight")))
        git.run(work, "switch", "-q", "main")
        commit(work, "Five on main", "a.txt" to lines(10, mapOf(5 to "five")))
        git.run(work, "switch", "-q", "feat")
        val (line, original) = featLine(since = "main~1")
        val plan = RebasePlan.of(line, original).withAction(c, PlanAction.FIXUP).copy(onto = OntoBase(MAIN, hash("main")))

        val result = check(plan)
        val rebased = rebaseLikeGit(hash("main"), hash("main~1"), listOf("pick $a", "pick $b", "fixup $c"))

        assertNull(result.stop)
        assertEquals(listOf(a, b), result.groups.map { it.target })
        assertEquals(setOf(b), result.groups[1].emptyCommits)
        assertTrue(!result.groups[1].isLeftOut)
        assertEquals(lines(10, mapOf(2 to "two", 5 to "five", 8 to "eight")), git.run(work, "show", "${result.groups[1].tree}:a.txt"))
        // git folds the fixup into the commit below it, Two, which then has the fixup's change too
        assertEquals(1, rebased.trees.size)
    }

    @Test
    fun `a drop that a later commit needs stops at that commit, with the file that conflicts`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "Add the cache", "cache.txt" to lines(5))
        val b = commit(work, "Fix the cache", "cache.txt" to lines(5, mapOf(3 to "fixed")))
        val c = commit(work, "Other", "a.txt" to lines(10, mapOf(1 to "one")))
        val (line, original) = featLine()
        val plan = RebasePlan.of(line, original).withAction(a, PlanAction.DROP)

        val result = check(plan)
        val rebased = rebaseLikeGit(hash("main"), hash("main"), listOf("drop $a", "pick $b", "pick $c"))

        assertEquals(PlanStop.Conflict(b, listOf("cache.txt")), result.stop)
        assertEquals(b, rebased.stoppedAt)
        assertEquals(setOf("cache.txt"), rebased.conflicts)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `an edit stops after the commit, and the groups before it are checked`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to "a\n")
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "b.txt" to "b\n")
        val b = commit(work, "B", "c.txt" to "c\n")
        val c = commit(work, "C", "d.txt" to "d\n")
        val (line, original) = featLine()

        val result = check(RebasePlan.of(line, original).withAction(b, PlanAction.EDIT).withAction(c, PlanAction.FIXUP))

        // git stops after picking B, before the fixup that joins it
        assertEquals(PlanStop.Edit(b), result.stop)
        assertEquals(listOf(a), result.groups.map { it.target })
    }

    @Test
    fun `a branch from the repository's first commit starts from the empty tree`(): Unit = runBlocking {
        git.run(work, "checkout", "-q", "-b", "feat")
        val a = commit(work, "A", "a.txt" to "a\n")
        val b = commit(work, "B", "b.txt" to "b\n")
        val c = commit(work, "C", "a.txt" to "a2\n")
        val (line, original) = featLine(base = null)
        val plan = RebasePlan(line, original, listOf(PlanRow(commitOf(b), PlanAction.PICK), PlanRow(commitOf(a), PlanAction.PICK), PlanRow(commitOf(c), PlanAction.PICK)))

        val result = check(plan)

        assertNull(result.stop)
        assertEquals(tree(c), result.groups.last().tree)
        val firstTree = git.run(work, "ls-tree", "--name-only", result.groups.first().tree)

        assertEquals(listOf("b.txt"), firstTree.lines().filter { it.isNotBlank() })
    }

    @Test
    fun `a missing object makes a plan one that Leaf can't tell about`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        commit(work, "Two", "a.txt" to lines(10, mapOf(2 to "two")))
        git.run(work, "switch", "-q", "main")
        commit(work, "Nine", "a.txt" to lines(10, mapOf(9 to "nine")))
        git.run(work, "switch", "-q", "feat")
        val blob = hash("main:a.txt")
        File(work, ".git/objects/${blob.take(2)}/${blob.drop(2)}").delete()
        val (line, original) = featLine(since = "main~1")

        val result = check(RebasePlan.of(line, original).copy(onto = OntoBase(MAIN, hash("main"))))

        assertEquals(PlanStop.Unknown(UnknownStopReason.MISSING_OBJECTS), result.stop)
    }

    @Test
    fun `in a partial clone, the check downloads nothing and can't tell about a plan whose merges need a file`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        commit(work, "Two", "a.txt" to lines(10, mapOf(2 to "two")))
        git.run(work, "switch", "-q", "main")
        commit(work, "Nine", "a.txt" to lines(10, mapOf(9 to "nine")))
        git.run(work, "config", "uploadpack.allowFilter", "true")
        val source = work
        work = File(root, "clone")
        git.run(root, "clone", "-q", "--filter=blob:none", "--branch", "feat", "file://${source.path}", work.path)
        git.run(work, "branch", "-q", "main", "origin/main")
        val mainBlob = hash("main:a.txt")
        val (line, original) = featLine(since = "main~1")

        val result = check(RebasePlan.of(line, original).copy(onto = OntoBase(MAIN, hash("main"))))

        assertEquals(PlanStop.Unknown(UnknownStopReason.MISSING_OBJECTS), result.stop)

        // main's file is still missing: the check downloaded nothing
        val missing = git.run(work, mapOf("GIT_NO_LAZY_FETCH" to "1"), "rev-list", "--objects", "--missing=print", "main")
            .lines().filter { it.startsWith("?") }.map { it.removePrefix("?") }

        assertTrue(mainBlob in missing, "Missing: $missing")
    }

    @Test
    fun `a plan checked again merges only what changed since`(): Unit = runBlocking {
        commit(work, "Base", "a.txt" to lines(10))
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "One", "a.txt" to lines(10, mapOf(1 to "one")))
        commit(work, "Three", "a.txt" to lines(10, mapOf(1 to "one", 3 to "three")))
        val c = commit(work, "Five", "a.txt" to lines(10, mapOf(1 to "one", 3 to "three", 5 to "five")))
        val (line, original) = featLine()
        val plan = RebasePlan.of(line, original).withAction(a, PlanAction.DROP)

        check(plan)
        val first = merges()
        check(plan)
        assertEquals(first, merges())

        // Rewording the last commit doesn't merge again: its tree comes from the merges before it
        check(plan.withMessage(c, "Five, now"))
        assertEquals(first, merges())
        assertEquals(2, first)
    }
}
