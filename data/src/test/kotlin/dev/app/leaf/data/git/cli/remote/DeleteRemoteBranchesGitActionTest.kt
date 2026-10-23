// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.errors.SshNeedsGitError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Deleting several of a remote's branches on the server ([DeleteRemoteBranchesGitAction]), in a bare repository on
 * disk, with hooks that refuse as servers do. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class DeleteRemoteBranchesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var bare: File
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper!!, globalConfig)

        bare = File(tempDir, "origin.git")
        git.run(tempDir, "init", "--bare", bare.absolutePath)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.absolutePath, "main")

        work = File(tempDir, "work")
        git.run(tempDir, "clone", bare.absolutePath, work.absolutePath)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `deletes several branches in one push, with their remote-tracking branches`(): Unit = runBlocking {
        pushBranches("claude/a", "claude/b", "keep")
        val targets = listOf(target("claude/a"), target("claude/b"))

        val result = delete(targets)

        assertEquals(BranchesDeletion(targets.map { BranchDeletionOutcome.Deleted(it) }), result)
        assertEquals(listOf("refs/heads/keep", "refs/heads/main"), serverBranches())
        assertEquals(listOf("origin/HEAD", "origin/keep", "origin/main"), trackingBranches())
        assertEquals(targets.joinToString("\n") { "${it.simpleName} ${it.tip}" }, result.deletedList())
        assertNull(result.remoteMessage)
    }

    @Test
    fun `a branch someone pushed to since the fetch stays, the others go`(): Unit = runBlocking {
        pushBranches("pushed-to", "done")
        val targets = listOf(target("pushed-to"), target("done"))
        val now = pushFromAnotherClone("pushed-to")

        val result = delete(targets)

        assertEquals(
            listOf(
                BranchDeletionOutcome.Kept(targets[0], BranchKept.ChangedOnRemote(now)),
                BranchDeletionOutcome.Deleted(targets[1]),
            ),
            result.outcomes,
        )
        assertEquals(now, serverRef("refs/heads/pushed-to"), "The server keeps the commit someone pushed")
        assertTrue("origin/pushed-to" in trackingBranches(), "Its remote-tracking branch stays, for a fetch to update")
    }

    @Test
    fun `a branch someone deleted on the server since the fetch counts as deleted, and loses its remote-tracking branch`(): Unit =
        runBlocking {
            pushBranches("gone")
            val target = target("gone")
            git.run(cloneOf("other"), "push", "origin", "--delete", "gone")

            assertEquals(listOf(BranchDeletionOutcome.Deleted(target)), delete(listOf(target)).outcomes)
            assertFalse("origin/gone" in trackingBranches())
        }

    @Test
    fun `deleting anyway, at the commit the server said, deletes what someone pushed`(): Unit = runBlocking {
        pushBranches("pushed-to")
        val target = target("pushed-to")
        val now = pushFromAnotherClone("pushed-to")

        val refused = delete(listOf(target))
        val anyway = refused.changedOnRemote

        assertEquals(listOf(BranchToDelete(target.name, target.tip, serverTip = now)), anyway)
        assertEquals(listOf(BranchDeletionOutcome.Deleted(anyway.single())), delete(anyway).outcomes)
        assertFalse("refs/heads/pushed-to" in serverBranches())
        assertFalse("origin/pushed-to" in trackingBranches())
    }

    @Test
    fun `deleting anyway still stops at another push`(): Unit = runBlocking {
        pushBranches("pushed-to")
        val target = target("pushed-to")
        pushFromAnotherClone("pushed-to")
        val anyway = delete(listOf(target)).changedOnRemote
        val third = pushFromAnotherClone("pushed-to")

        assertEquals(
            listOf(BranchDeletionOutcome.Kept(anyway.single(), BranchKept.ChangedOnRemote(third))),
            delete(anyway).outcomes,
        )
        assertEquals(third, serverRef("refs/heads/pushed-to"))
    }

    @Test
    fun `a remote-tracking branch that a fetch moved since the dialog read it stays, and nothing is pushed for it`(): Unit =
        runBlocking {
            pushBranches("moving")
            val target = target("moving")
            val now = pushFromAnotherClone("moving")
            git.run(work, "fetch", "origin")

            assertEquals(
                listOf(BranchDeletionOutcome.Kept(target, BranchKept.Moved(now))),
                delete(listOf(target)).outcomes,
            )
            assertEquals(now, serverRef("refs/heads/moving"))
        }

    @Test
    fun `a remote-tracking branch that's gone stays gone`(): Unit = runBlocking {
        pushBranches("pruned")
        val target = target("pruned")
        git.run(work, "branch", "-r", "-d", "origin/pruned")

        assertEquals(listOf(BranchDeletionOutcome.Kept(target, BranchKept.NoLongerExists)), delete(listOf(target)).outcomes)
        assertTrue("refs/heads/pruned" in serverBranches())
    }

    @Test
    fun `a branch that the server's update hook protects stays, the others go, with what the server said`(): Unit =
        runBlocking {
            pushBranches("protected", "free")
            File(bare, "hooks/update").writeExecutable(
                "#!/bin/sh\nif [ \"$1\" = refs/heads/protected ]; then echo \"error: protected is protected\" >&2; exit 1; fi\n"
            )
            val targets = listOf(target("protected"), target("free"))

            val result = delete(targets)

            assertEquals(
                listOf(
                    BranchDeletionOutcome.Kept(targets[0], BranchKept.RefusedByRemote("hook declined")),
                    BranchDeletionOutcome.Deleted(targets[1]),
                ),
                result.outcomes,
            )
            assertTrue(result.remoteMessage.orEmpty().contains("error: protected is protected")) { "${result.remoteMessage}" }
            assertEquals(listOf("refs/heads/main", "refs/heads/protected"), serverBranches())
        }

    @Test
    fun `a pre-receive hook that refuses one branch refuses the whole push`(): Unit = runBlocking {
        pushBranches("protected", "free")
        File(bare, "hooks/pre-receive").writeExecutable(
            "#!/bin/sh\nwhile read old new ref; do [ \"\$ref\" = refs/heads/protected ] && exit 1; done\nexit 0\n"
        )
        val targets = listOf(target("protected"), target("free"))

        assertEquals(
            targets.map { BranchDeletionOutcome.Kept(it, BranchKept.RefusedByRemote("pre-receive hook declined")) },
            delete(targets).outcomes,
        )
        assertEquals(listOf("refs/heads/free", "refs/heads/main", "refs/heads/protected"), serverBranches())
    }

    @Test
    fun `the server refuses its default branch`(): Unit = runBlocking {
        val target = target("main")

        assertEquals(
            listOf(BranchDeletionOutcome.Kept(target, BranchKept.RefusedByRemote("deletion of the current branch prohibited"))),
            delete(listOf(target)).outcomes,
        )
        assertTrue("refs/heads/main" in serverBranches())
    }

    @Test
    fun `with two push URLs, a branch counts as deleted only when both deleted it`(): Unit = runBlocking {
        pushBranches("both", "one")
        // A mirror with "both" and not "one", pushed to first: git reports each branch once per push URL
        val mirror = File(tempDir, "mirror.git")
        git.run(tempDir, "init", "--bare", mirror.absolutePath)
        git.run(work, "push", mirror.absolutePath, "refs/remotes/origin/both:refs/heads/both")
        git.run(work, "remote", "set-url", "--add", "--push", "origin", mirror.absolutePath)
        git.run(work, "remote", "set-url", "--add", "--push", "origin", bare.absolutePath)
        val targets = listOf(target("both"), target("one"))

        val result = delete(targets)

        assertEquals(
            listOf(
                BranchDeletionOutcome.Deleted(targets[0]),
                // The mirror didn't have it: with several push URLs, the lease's refusal stays as git reported it
                BranchDeletionOutcome.Kept(targets[1], BranchKept.ChangedOnRemote(now = null)),
            ),
            result.outcomes,
        )
        assertEquals(listOf("refs/heads/main"), serverBranches(), "origin deleted both")
    }

    @Test
    fun `a single-branch clone's remote-tracking branches that git leaves are removed`(): Unit = runBlocking {
        pushBranches("feature")
        val single = File(tempDir, "single")
        git.run(tempDir, "clone", "--single-branch", bare.absolutePath, single.absolutePath)
        git.run(single, "fetch", "origin", "feature:refs/remotes/origin/feature")
        val target = BranchToDelete("refs/remotes/origin/feature", git.run(single, "rev-parse", "origin/feature").trim())

        val result = deleteIn(File(single, ".git").absolutePath, listOf(target))

        assertEquals(listOf(BranchDeletionOutcome.Deleted(target)), result.outcomes)
        assertFalse("refs/heads/feature" in serverBranches())
        assertEquals("", git.run(single, "for-each-ref", "refs/remotes/origin/feature").trim())
    }

    @Test
    fun `a branch is deleted by its name on the server, as the fetch refspec maps it`(): Unit = runBlocking {
        pushBranches("feature")
        git.run(work, "config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/heads/*")
        git.run(work, "fetch", "origin")
        val target = BranchToDelete("refs/remotes/origin/heads/feature", git.run(work, "rev-parse", "origin/heads/feature").trim())

        assertEquals(listOf(BranchDeletionOutcome.Deleted(target)), delete(listOf(target)).outcomes)
        assertEquals(listOf("refs/heads/main"), serverBranches())
        assertEquals("", git.run(work, "for-each-ref", "refs/remotes/origin/heads/feature").trim())
    }

    @Test
    fun `branches with a slash and unusual characters in their names`(): Unit = runBlocking {
        val names = listOf("claude/eager-ride-4f2a", "feature/deep/er/name", "fix#12", "user@host", "a+b", "100%", "it's", "café")
        pushBranches(*names.toTypedArray())
        val targets = names.map(::target)

        assertEquals(targets.map { BranchDeletionOutcome.Deleted(it) }, delete(targets).outcomes)
        assertEquals(listOf("refs/heads/main"), serverBranches())
    }

    @Test
    fun `many branches go in pushes of at most a hundred`(): Unit = runBlocking {
        val names = (1..150).map { "claude/agent-branch-$it" }
        pushBranches(*names.toTypedArray())
        val counts = File(tempDir, "pushes.log")
        File(bare, "hooks/pre-receive").writeExecutable("#!/bin/sh\nwc -l | tr -d ' ' >> '${counts.absolutePath}'\n")
        val targets = names.map(::target)

        val result = delete(targets)

        assertEquals(targets.map { BranchDeletionOutcome.Deleted(it) }, result.outcomes)
        assertEquals(listOf("100", "50"), counts.readLines())
        assertEquals(listOf("refs/heads/main"), serverBranches())
    }

    @Test
    fun `a remote that can't be reached is an error, and nothing changes`(): Unit = runBlocking {
        pushBranches("feature")
        val target = target("feature")
        git.run(work, "remote", "set-url", "origin", File(tempDir, "missing.git").absolutePath)

        val result = action()(gitDir, "origin", listOf(target))

        assertInstanceOf(Either.Err::class.java, result)
        assertInstanceOf(RemoteOperationError::class.java, (result as Either.Err).error)
        assertTrue("origin/feature" in trackingBranches())
    }

    @Test
    fun `without git for remote operations, it says that git is needed and runs nothing`(): Unit = runBlocking {
        pushBranches("feature")
        val off = DeleteRemoteBranchesGitAction(
            jgit,
            testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper(), remoteOperationsWithGit = false),
            remote.command,
        )

        assertEquals(
            Either.Err(RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff)),
            off(gitDir, "origin", listOf(target("feature"))),
        )
        assertTrue("refs/heads/feature" in serverBranches())
    }

    @Test
    fun `a branch of another remote isn't pushed to this one`(): Unit = runBlocking {
        val target = BranchToDelete("refs/remotes/upstream/feature", "a".repeat(40))

        assertEquals(
            listOf(BranchDeletionOutcome.Kept(target, BranchKept.Failed("refs/remotes/upstream/feature isn't a branch of origin."))),
            delete(listOf(target)).outcomes,
        )
    }

    @Test
    fun `cancelling stops git and the hooks it started`(): Unit = runBlocking {
        pushBranches("slow")
        val started = File(tempDir, "started")
        val finished = File(tempDir, "finished")
        File(bare, "hooks/pre-receive").writeExecutable(
            "#!/bin/sh\ntouch '${started.absolutePath}'\nsleep 30\ntouch '${finished.absolutePath}'\n"
        )

        val deleting = async(Dispatchers.IO) { action()(gitDir, "origin", listOf(target("slow"))) }

        withTimeout(20.seconds) {
            while (!started.exists()) delay(20)
        }

        deleting.cancel()

        val cancelled = withTimeout(10.seconds) {
            try {
                deleting.await()
                false
            } catch (e: CancellationException) {
                true
            }
        }

        assertTrue(cancelled)
        delay(500)
        assertFalse(finished.exists(), "The hook was stopped with git")
        assertTrue("refs/heads/slow" in serverBranches())
    }

    @Test
    fun `a push names each branch with its lease, the remote, then the branches`() {
        val branches = listOf(
            PushedBranch(BranchToDelete("refs/remotes/origin/a", "1".repeat(40)), "refs/heads/a"),
            PushedBranch(BranchToDelete("refs/remotes/origin/b", "2".repeat(40)), "refs/heads/b"),
        )

        assertEquals(
            listOf(
                "push", "--porcelain", "--progress",
                "--force-with-lease=refs/heads/a:${"1".repeat(40)}",
                "--force-with-lease=refs/heads/b:${"2".repeat(40)}",
                "origin", "--delete", "refs/heads/a", "refs/heads/b",
            ),
            pushArguments("origin", branches),
        )
    }

    @Test
    fun `pushes stay under a hundred branches and their arguments under 24,000 characters`() {
        fun branches(count: Int, nameLength: Int) = (1..count).map {
            val name = "refs/heads/" + "x".repeat(nameLength) + it
            PushedBranch(BranchToDelete(name, "a".repeat(40)), name)
        }

        assertEquals(listOf(100, 100, 50), chunked(branches(250, 5)).map { it.size })
        assertEquals(emptyList<Int>(), chunked(emptyList()).map { it.size })

        // Names of about 250 characters, as long as a loose ref's file name can be, give fewer than a hundred a push
        val long = chunked(branches(90, 240))
        assertTrue(long.size > 1, "${long.map { it.size }}")
        assertTrue(long.all { chunk -> pushArguments("origin", chunk).sumOf { it.length + 1 } <= 24_100 })
        assertEquals(90, long.sumOf { it.size })
    }

    @Test
    fun `the server's lines are git's remote lines without the prefix`() {
        val stderr = "remote: error: protected is protected        \nremote: \nTo /x/origin.git\n" +
            "error: failed to push some refs to '/x/origin.git'\r\nremote: warning: deleting a non-existent ref\r\n"

        assertEquals(
            listOf("error: protected is protected", "warning: deleting a non-existent ref"),
            remoteLines(stderr),
        )
    }

    private fun action() = DeleteRemoteBranchesGitAction(
        jgit,
        testRemoteOperationsBackend(jgit, remote.gitCli, builtAskpassHelper()),
        remote.command,
    )

    private suspend fun delete(targets: List<BranchToDelete>) = deleteIn(gitDir, targets)

    private suspend fun deleteIn(gitDir: String, targets: List<BranchToDelete>): BranchesDeletion =
        when (val result = action()(gitDir, "origin", targets)) {
            is Either.Ok -> result.value
            is Either.Err -> throw AssertionError("The action failed: ${result.error}")
        }

    private fun target(name: String) =
        BranchToDelete("refs/remotes/origin/$name", git.run(work, "rev-parse", "refs/remotes/origin/$name").trim())

    /** Pushes each of [names] to origin from main, with a commit of its own, and fetches them. */
    private fun pushBranches(vararg names: String) {
        for (name in names) {
            git.run(work, "switch", "-c", name)
            File(work, "file-${names.indexOf(name)}.txt").writeText("$name\n")
            git.run(work, "add", ".")
            git.run(work, "commit", "-m", "Work on $name")
            git.run(work, "switch", "main")
        }

        git.run(work, "push", "origin", *names.map { "refs/heads/$it:refs/heads/$it" }.toTypedArray())
        names.forEach { git.run(work, "branch", "-D", "--", it) }
    }

    /** A clone of origin next to [work], for what someone else does. */
    private fun cloneOf(name: String): File {
        val clone = File(tempDir, name)
        git.run(tempDir, "clone", bare.absolutePath, clone.absolutePath)

        return clone
    }

    /** Someone else pushes a commit to [branch], and the commit's hash. */
    private fun pushFromAnotherClone(branch: String): String {
        val existing = File(tempDir, "other-$branch").takeIf { it.isDirectory }
        val clone = existing ?: cloneOf("other-$branch")
        if (existing == null) git.run(clone, "switch", branch)
        File(clone, "other.txt").appendText("someone else\n")
        git.run(clone, "add", ".")
        git.run(clone, "commit", "-m", "Someone else's work")
        git.run(clone, "push", "origin", branch)

        return git.run(clone, "rev-parse", "HEAD").trim()
    }

    private fun serverBranches(): List<String> =
        git.run(bare, "for-each-ref", "--format=%(refname)", "refs/heads").lines().filter { it.isNotBlank() }

    private fun serverRef(name: String): String = git.run(bare, "rev-parse", name).trim()

    // Not %(refname:short), which gives origin/HEAD as "origin"
    private fun trackingBranches(): List<String> =
        git.run(work, "for-each-ref", "--format=%(refname)", "refs/remotes")
            .lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("refs/remotes/") }
}
