// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cherrypick

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.cherrypick.BranchAhead
import dev.app.leaf.domain.cherrypick.CherryPickLeftOut
import dev.app.leaf.domain.cherrypick.CherryPickSequence
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The git actions behind cherry-picking a branch's commits (fork-only), each checked against git's own answer: the
 * branch menus' counts against `git rev-list --count`, the dialog's commits against `git rev-list`, and the sequence
 * against git's todo.
 */
@DisabledOnOs(OS.WINDOWS)
class CherryPickGitActionsTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val jgit by lazy { testJGit() }
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = File(root, "work")
        git.run(root, "init", "-q", work.path)
        commit("base", "a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    private fun commit(message: String, vararg files: Pair<String, String>) {
        for ((path, text) in files) {
            File(work, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        git.run(work, "add", "-A")
        git.run(work, "commit", "-q", "--allow-empty", "-m", message)
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    /**
     * main: base, its own commit, then a copy of feature's first commit. feature: three commits of its own, the first
     * of which main has a copy of, then a merge of main from before the copy. done: an ancestor of main. other: one commit.
     */
    private fun branches() {
        git.run(work, "branch", "done")
        git.run(work, "switch", "-q", "-c", "feature")
        commit("feature: one", "a.txt" to "a one\n")
        commit("feature: two", "b.txt" to "b two\n")
        git.run(work, "switch", "-q", "main")
        commit("main: own", "d.txt" to "d\n")
        git.run(work, "switch", "-q", "feature")
        commit("feature: three\n\nWith a body\nof two lines.", "c.txt" to "c three\n")
        git.run(work, "merge", "-q", "--no-edit", "main")
        // A copy of feature's first commit, after the merge: a copy that feature had merged would be one of its own
        // commits to git's --cherry-mark, not a copy on the other side
        git.run(work, "switch", "-q", "main")
        git.run(work, "cherry-pick", "feature~3")
        git.run(work, "switch", "-q", "-c", "other", "main~2")
        commit("other: one", "e.txt" to "e\n")
        git.run(work, "switch", "-q", "main")
        git.run(work, "update-ref", "refs/remotes/origin/feature", "feature~1")
    }

    // The branch menus' counts

    @Test
    fun `each branch's count is what git rev-list counts, and its tip says whether it's a merge`(): Unit = runBlocking {
        branches()

        val ahead = (GetBranchesAheadGitAction(gitCli)(gitDir) as Either.Ok).value

        for (ref in listOf("refs/heads/feature", "refs/heads/other", "refs/remotes/origin/feature")) {
            val count = git.run(work, "rev-list", "--count", "HEAD..$ref").trim().toInt()
            val parents = git.run(work, "rev-list", "--parents", "-n", "1", ref).trim().split(' ').size - 1

            assertEquals(BranchAhead(count, tipIsMerge = parents > 1), ahead.byRef[ref], ref)
        }

        // Nothing to pick: main itself, and a branch that main already has
        assertEquals(setOf("refs/heads/feature", "refs/heads/other", "refs/remotes/origin/feature"), ahead.byRef.keys)
    }

    @Test
    fun `a linked worktree counts against its own HEAD`(): Unit = runBlocking {
        branches()
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "other")
        val linkedGitDir = git.run(linked, "rev-parse", "--absolute-git-dir").trim()

        val ahead = (GetBranchesAheadGitAction(gitCli)(linkedGitDir) as Either.Ok).value
        val mainCount = git.run(linked, "rev-list", "--count", "HEAD..refs/heads/main").trim().toInt()

        assertEquals(BranchAhead(mainCount, tipIsMerge = false), ahead.byRef["refs/heads/main"])
        assertNull(ahead.byRef["refs/heads/other"], "The worktree's own branch")
    }

    @Test
    fun `a repository without commits has nothing to count`(): Unit = runBlocking {
        val empty = File(root, "empty")
        git.run(root, "init", "-q", empty.path)

        val result = GetBranchesAheadGitAction(gitCli)(File(empty, ".git").path)

        assertEquals(emptyMap<String, BranchAhead>(), (result as Either.Ok).value.byRef)
    }

    @Test
    fun `git that can't count still says which branches have commits to pick`(): Unit = runBlocking {
        branches()
        val realGit = git.run(work, "--exec-path").trim().let { File(File(it).parentFile.parentFile, "bin/git") }
            .takeIf { it.canExecute() }?.path ?: "git"
        // git before 2.41: %(ahead-behind) is an unknown field
        val oldGit = File(root, "old-git").apply {
            writeExecutable(
                """
                #!/bin/sh
                case "${'$'}*" in
                  *ahead-behind*) echo "fatal: unknown field name: ahead-behind:HEAD" >&2; exit 128 ;;
                esac
                exec $realGit "${'$'}@"
                """.trimIndent() + "\n"
            )
        }
        val oldGitCli = testGitCli(
            shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"),
            configuredPath = oldGit.path,
        )

        val ahead = (GetBranchesAheadGitAction(oldGitCli)(gitDir) as Either.Ok).value

        assertEquals(
            mapOf(
                "refs/heads/feature" to BranchAhead(null, tipIsMerge = true),
                "refs/heads/other" to BranchAhead(null, tipIsMerge = false),
                "refs/remotes/origin/feature" to BranchAhead(null, tipIsMerge = false),
            ),
            ahead.byRef,
        )
    }

    // The dialog's commits

    @Test
    fun `the commits are git's, parents first, with the merge and the copy that main has left out`(): Unit = runBlocking {
        branches()
        val feature = Branch(hash("feature"), "refs/heads/feature", isLocal = true)

        val result = (GetBranchCherryPickCommitsGitAction(gitCli, jgit)(gitDir, feature, limit = 500) as Either.Ok).value
        val expected = git.run(work, "rev-list", "--reverse", "--topo-order", "HEAD..refs/heads/feature").trim().lines()

        assertEquals(expected, result.candidates.map { it.commit.hash })
        assertEquals(expected.size, result.commitCount)
        assertEquals(
            listOf(CherryPickLeftOut.ALREADY_APPLIED, null, null, CherryPickLeftOut.MERGE),
            result.candidates.map { it.leftOut },
        )

        val three = result.candidates[2].commit

        assertEquals("feature: three\n\nWith a body\nof two lines.", three.message)
        assertEquals("Leaf Test", three.author.name)
        assertEquals(git.run(work, "log", "-1", "--format=%at", three.hash).trim().toLong(), three.authorDate)
        assertEquals(listOf(hash("feature~2")), three.parentsHashes)
    }

    @Test
    fun `past the limit, only the count is read`(): Unit = runBlocking {
        branches()
        val feature = Branch(hash("feature"), "refs/heads/feature", isLocal = true)

        val result = (GetBranchCherryPickCommitsGitAction(gitCli, jgit)(gitDir, feature, limit = 3) as Either.Ok).value

        assertEquals(emptyList<Any>(), result.candidates)
        assertEquals(4, result.commitCount)
    }

    @Test
    fun `a tag named like the branch doesn't stand in for it`(): Unit = runBlocking {
        branches()
        git.run(work, "tag", "other", "main~2")
        val other = Branch(hash("refs/heads/other"), "refs/heads/other", isLocal = true)

        val result = (GetBranchCherryPickCommitsGitAction(gitCli, jgit)(gitDir, other, limit = 500) as Either.Ok).value

        assertEquals(listOf("other: one"), result.candidates.map { it.commit.shortMessage })
    }

    // git's sequencer

    /** Two commits, the first of which conflicts with main. */
    private fun conflictingCommits(): List<String> {
        git.run(work, "switch", "-q", "-c", "topic")
        commit("topic: one", "a.txt" to "a topic\n")
        commit("topic: two", "b.txt" to "b topic\n")
        git.run(work, "switch", "-q", "main")
        commit("main: a", "a.txt" to "a main\n")

        return listOf(hash("topic~1"), hash("topic"))
    }

    private fun todoHashes(dir: File = File(gitDir)) =
        File(dir, "sequencer/todo").readLines().filter { it.isNotBlank() }.map { it.split(' ')[1] }

    @Test
    fun `a cherry-pick that git stopped is read from its todo`(): Unit = runBlocking {
        val commits = conflictingCommits()
        git.runFailing(work, "cherry-pick", *commits.toTypedArray())

        val sequence = (GetCherryPickSequenceGitAction()(gitDir) as Either.Ok).value

        assertEquals(CherryPickSequence(todoHashes(), noCommit = false), sequence)
        assertEquals(2, sequence?.remaining?.size)
        assertTrue(commits[0].startsWith(sequence!!.remaining[0]))
    }

    @Test
    fun `one with no-commit says so`(): Unit = runBlocking {
        val commits = conflictingCommits()
        git.runFailing(work, "cherry-pick", "--no-commit", *commits.toTypedArray())

        assertEquals(true, (GetCherryPickSequenceGitAction()(gitDir) as Either.Ok).value?.noCommit)
    }

    @Test
    fun `a single cherry-pick and a revert have none`(): Unit = runBlocking {
        val commits = conflictingCommits()
        git.runFailing(work, "cherry-pick", commits[0])

        assertNull((GetCherryPickSequenceGitAction()(gitDir) as Either.Ok).value, "git keeps no sequencer for one commit")

        git.run(work, "cherry-pick", "--abort")
        commit("main: b", "b.txt" to "b main\n")
        commit("main: b again", "b.txt" to "b main again\n")
        git.runFailing(work, "revert", "HEAD~1", "HEAD~2")

        assertTrue(File(gitDir, "sequencer/todo").exists())
        assertNull((GetCherryPickSequenceGitAction()(gitDir) as Either.Ok).value, "A revert's")
    }

    @Test
    fun `a linked worktree has its own`(): Unit = runBlocking {
        val commits = conflictingCommits()
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", "-b", "elsewhere", linked.path, "main")
        val linkedGitDir = git.run(linked, "rev-parse", "--absolute-git-dir").trim()
        git.runFailing(linked, "cherry-pick", *commits.toTypedArray())

        assertEquals(
            CherryPickSequence(todoHashes(File(linkedGitDir)), noCommit = false),
            (GetCherryPickSequenceGitAction()(linkedGitDir) as Either.Ok).value,
        )
        assertNull((GetCherryPickSequenceGitAction()(gitDir) as Either.Ok).value, "The main worktree has none")
    }
}
