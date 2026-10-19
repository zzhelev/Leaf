// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
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
 * What a rebase's Continue and Skip do with files that aren't staged, in git's rebases (both backends, 2.54) and JGit's
 * (7.7). These are the findings that the status pane's rules rest on (`FinishBlockers`, Gitnuro#120): git refuses to
 * continue with unstaged changes to tracked files, and not with untracked files. JGit continues with both.
 *
 * Every rebase starts at the same conflict: `feature` and `main` both changed a.txt, so `git rebase main` stops there.
 * b.txt and c.txt are tracked files that the tests change, u.txt is an untracked one.
 */
@DisabledOnOs(OS.WINDOWS)
class RebaseWithUnstagedFilesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private var repositories = 0

    private enum class Kind(val isGit: Boolean) {
        GIT_MERGE_BACKEND(true),
        GIT_APPLY_BACKEND(true),
        JGIT(false),
    }

    private val allKinds = Kind.entries
    private val gitKinds = Kind.entries.filter { it.isGit }

    @BeforeEach
    fun isolateConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    /** A repository whose rebase of `feature` onto `main` is stopped at the conflict in a.txt, started by [kind]. */
    private fun stoppedRebase(kind: Kind): File {
        val repo = File(tempDir, "repo-${repositories++}")

        repo.mkdirs()
        git.run(repo, "init", "-b", "main")
        File(repo, "a.txt").writeText("one\n")
        File(repo, "b.txt").writeText("bee\n")
        File(repo, "c.txt").writeText("sea\n")
        git.run(repo, "add", ".")
        git.run(repo, "commit", "-m", "base")
        git.run(repo, "switch", "-c", "feature")
        File(repo, "a.txt").writeText("feature\n")
        git.run(repo, "commit", "-am", "feature a")
        git.run(repo, "switch", "main")
        File(repo, "a.txt").writeText("main\n")
        git.run(repo, "commit", "-am", "main a")
        git.run(repo, "switch", "feature")

        when (kind) {
            Kind.GIT_MERGE_BACKEND -> git.runFailing(repo, "rebase", "main")
            Kind.GIT_APPLY_BACKEND -> git.runFailing(repo, "-c", "rebase.backend=apply", "rebase", "main")
            Kind.JGIT -> Git.open(repo).use { jgit ->
                val result = jgit.rebase().setUpstream(jgit.repository.resolve("main")).call()
                assertEquals(RebaseResult.Status.STOPPED, result.status)
            }
        }

        return repo
    }

    private fun resolve(repo: File) {
        File(repo, "a.txt").writeText("resolved\n")
        git.run(repo, "add", "a.txt")
    }

    /** Takes main's version, which is HEAD's: the commit being applied comes out empty. */
    private fun resolveToHead(repo: File) {
        File(repo, "a.txt").writeText("main\n")
        git.run(repo, "add", "a.txt")
    }

    private fun untracked(repo: File) = File(repo, "u.txt").writeText("x\n")

    private fun isRunning(repo: File) =
        File(repo, ".git/rebase-merge").isDirectory || File(repo, ".git/rebase-apply").isDirectory

    private fun headSubject(repo: File) = git.run(repo, "log", "-1", "--format=%s").trim()

    private fun status(repo: File) = git.run(repo, "status", "--short").lines().filter { it.isNotBlank() }

    private fun text(repo: File, name: String) = File(repo, name).readText()

    /** What Continue did: the rebase went on, git refused with its message, or JGit had nothing to commit. */
    private sealed interface Continued {
        data object Went : Continued
        data class Refused(val message: String) : Continued
        data object NothingToCommit : Continued
    }

    private fun continueRebase(kind: Kind, repo: File): Continued {
        if (kind.isGit) {
            return try {
                git.run(repo, mapOf("GIT_EDITOR" to ":", "LC_ALL" to "C"), "rebase", "--continue")
                Continued.Went
            } catch (e: IllegalStateException) {
                Continued.Refused(e.message.orEmpty())
            }
        }

        return Git.open(repo).use { jgit ->
            try {
                when (val result = jgit.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call().status) {
                    RebaseResult.Status.NOTHING_TO_COMMIT -> Continued.NothingToCommit
                    RebaseResult.Status.OK, RebaseResult.Status.UP_TO_DATE -> Continued.Went
                    else -> error("$result")
                }
            } catch (e: Exception) {
                Continued.Refused("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun skipRebase(kind: Kind, repo: File) {
        if (kind.isGit) {
            git.run(repo, mapOf("GIT_EDITOR" to ":"), "rebase", "--skip")
        } else {
            Git.open(repo).use { jgit ->
                assertEquals(
                    RebaseResult.Status.OK,
                    jgit.rebase().setOperation(RebaseCommand.Operation.SKIP).call().status,
                )
            }
        }
    }

    private fun assertRefusedByGit(continued: Continued, message: String) {
        assertTrue(continued is Continued.Refused, "$continued")
        assertTrue(message in (continued as Continued.Refused).message, continued.message)
    }

    @Test
    fun `Continue goes on with an untracked file around, in every rebase`(): Unit {
        for (kind in allKinds) {
            val repo = stoppedRebase(kind)
            resolve(repo)
            untracked(repo)

            assertEquals(Continued.Went, continueRebase(kind, repo), "$kind")

            assertFalse(isRunning(repo), "$kind")
            assertEquals("feature a", headSubject(repo), "$kind")
            assertEquals(listOf("?? u.txt"), status(repo), "$kind")
        }
    }

    @Test
    fun `git refuses to continue with a tracked file modified and not staged`(): Unit {
        for (kind in gitKinds) {
            val repo = stoppedRebase(kind)
            resolve(repo)
            File(repo, "b.txt").writeText("changed\n")

            assertRefusedByGit(continueRebase(kind, repo), "You must edit all merge conflicts")

            assertTrue(isRunning(repo), "$kind")
            assertEquals(listOf("M  a.txt", " M b.txt"), status(repo), "$kind")
        }
    }

    @Test
    fun `git refuses to continue with a tracked file deleted and not staged`(): Unit {
        for (kind in gitKinds) {
            val repo = stoppedRebase(kind)
            resolve(repo)
            File(repo, "c.txt").delete()

            assertRefusedByGit(continueRebase(kind, repo), "You must edit all merge conflicts")

            assertTrue(isRunning(repo), "$kind")
        }
    }

    @Test
    fun `git refuses to continue with a file staged and changed again`(): Unit {
        for (kind in gitKinds) {
            val repo = stoppedRebase(kind)
            resolve(repo)
            File(repo, "b.txt").writeText("changed\n")
            git.run(repo, "add", "b.txt")
            File(repo, "b.txt").appendText("more\n")

            assertRefusedByGit(continueRebase(kind, repo), "You must edit all merge conflicts")
        }
    }

    @Test
    fun `git refuses to continue with the resolved file edited again after it was staged`(): Unit {
        for (kind in gitKinds) {
            val repo = stoppedRebase(kind)
            resolve(repo)
            File(repo, "a.txt").appendText("more\n")

            assertRefusedByGit(continueRebase(kind, repo), "You must edit all merge conflicts")
        }
    }

    @Test
    fun `JGit continues with tracked changes that are not staged, which git would refuse`() {
        // The status pane applies git's rule to JGit's rebases too, so that a rebase behaves the same whoever started it
        val modified = stoppedRebase(Kind.JGIT)
        resolve(modified)
        File(modified, "b.txt").writeText("changed\n")

        assertEquals(Continued.Went, continueRebase(Kind.JGIT, modified))
        assertFalse(isRunning(modified))
        assertEquals(listOf(" M b.txt"), status(modified))

        val deleted = stoppedRebase(Kind.JGIT)
        resolve(deleted)
        File(deleted, "c.txt").delete()

        assertEquals(Continued.Went, continueRebase(Kind.JGIT, deleted))
        assertEquals(listOf(" D c.txt"), status(deleted))

        // It commits what is staged, and leaves the later edit of the resolved file unstaged
        val edited = stoppedRebase(Kind.JGIT)
        resolve(edited)
        File(edited, "a.txt").appendText("more\n")

        assertEquals(Continued.Went, continueRebase(Kind.JGIT, edited))
        assertEquals("resolved\n", git.run(edited, "show", "HEAD:a.txt"))
        assertEquals(listOf(" M a.txt"), status(edited))
    }

    @Test
    fun `no rebase continues with a conflict that isn't resolved, an untracked file or not`() {
        for (kind in allKinds) {
            val repo = stoppedRebase(kind)
            untracked(repo)

            val continued = continueRebase(kind, repo)

            assertTrue(continued is Continued.Refused, "$kind $continued")
            assertTrue(isRunning(repo), "$kind")
            assertEquals(listOf("UU a.txt", "?? u.txt"), status(repo), "$kind")
        }
    }

    @Test
    fun `with the commit's changes resolved away, git's merge backend drops it, and the others don't continue`() {
        // Nothing is staged. This is why the status pane offers Skip there, and not Continue.
        val merge = stoppedRebase(Kind.GIT_MERGE_BACKEND)
        resolveToHead(merge)
        untracked(merge)

        assertEquals(Continued.Went, continueRebase(Kind.GIT_MERGE_BACKEND, merge))
        assertFalse(isRunning(merge))
        assertEquals("main a", headSubject(merge))

        val apply = stoppedRebase(Kind.GIT_APPLY_BACKEND)
        resolveToHead(apply)
        untracked(apply)

        assertRefusedByGit(continueRebase(Kind.GIT_APPLY_BACKEND, apply), "git rebase --skip")
        assertTrue(isRunning(apply))

        // JGit does nothing, and says so as a status that a caller can take for success
        val jgit = stoppedRebase(Kind.JGIT)
        resolveToHead(jgit)
        untracked(jgit)

        assertEquals(Continued.NothingToCommit, continueRebase(Kind.JGIT, jgit))
        assertTrue(isRunning(jgit))
        assertEquals("main a", headSubject(jgit))
    }

    @Test
    fun `Skip keeps an untracked file, in every rebase`(): Unit {
        for (kind in allKinds) {
            val repo = stoppedRebase(kind)
            untracked(repo)

            skipRebase(kind, repo)

            assertFalse(isRunning(repo), "$kind")
            assertEquals("main a", headSubject(repo), "$kind")
            assertEquals("x\n", text(repo, "u.txt"), "$kind")
        }
    }

    @Test
    fun `Skip discards changes to tracked files, so it is only offered when there are none`(): Unit {
        for (kind in allKinds) {
            val repo = stoppedRebase(kind)
            File(repo, "b.txt").writeText("changed\n")
            File(repo, "c.txt").delete()

            skipRebase(kind, repo)

            assertFalse(isRunning(repo), "$kind")
            assertEquals("bee\n", text(repo, "b.txt"), "$kind")
            assertEquals("sea\n", text(repo, "c.txt"), "$kind")
            assertEquals(emptyList<String>(), status(repo), "$kind")
        }
    }
}
