// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.log.RevertCommitGitAction
import dev.app.leaf.data.git.rebase.ContinueRebaseGitAction
import dev.app.leaf.data.git.rebase.GitCliRebase
import dev.app.leaf.data.git.rebase.RebaseBranchGitAction
import dev.app.leaf.data.git.rebase.SelectingContinueRebaseGitAction
import dev.app.leaf.data.git.rebase.isRebaseStartedByGit
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PASSPHRASE = "correct horse"

private const val CHERRY_PICK_STOPPED =
    "Cherry-pick stopped with conflicts. Fix the conflicts and commit the desired changes."

private const val REVERT_STOPPED = "Revert stopped with conflicts. Fix the conflicts and commit the desired changes."

/**
 * Merge, cherry-pick, revert and rebase through the git CLI ([GitCliOperations]): what git does in a terminal, git's
 * messages and hooks, the identity and signing settings that git reads, and JGit when git can't run them.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliOperationsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }
    private val home by lazy { File(root, "home") }
    private val globalConfig by lazy { File(home, ".gitconfig") }

    /** What the login shell adds: git reads [globalConfig], and ssh-keygen finds no agent. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to home.path,
            "SSH_AUTH_SOCK" to "/nonexistent/leaf-test-agent",
        )
    }

    private val jgit = testJGit()
    private val credentialsStateManager = CredentialsStateManager()
    private val gitCli by lazy { testGitCli(shellVariables) }
    private val operations by lazy { testGitCliOperations(jgit, gitCli, credentialsStateManager) }
    private val git by lazy { TestGitCli(globalConfig) }
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        assumeTrue(builtAskpassHelper() != null, "leaf-askpass isn't built, run ./gradlew :app:rustTasks")
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        home.mkdirs()
        // git refuses to make an identity up on Linux
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = git.initRepository(File(root, "work"))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `merges with git's message, fast-forwarding when it can`(): Unit = runBlocking {
        commitOnBranch("first", "first.txt", "first\n")
        assertEquals(Either.Ok(false), merge(localBranch("first")))
        assertEquals(rev("first"), rev("HEAD"))

        commitOnBranch("second", "second.txt", "second\n")
        commit("main.txt", "main\n")
        assertEquals(Either.Ok(false), merge(localBranch("second")))

        assertEquals("Merge branch 'second'", subject("HEAD"))
        assertEquals(rev("second"), rev("HEAD^2"))
    }

    @Test
    fun `makes a merge commit when the setting turns fast-forward off`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")

        assertEquals(Either.Ok(false), merge(localBranch("feature"), fastForward = false))

        assertEquals("Merge branch 'feature'", subject("HEAD"))
        assertEquals(rev("feature"), rev("HEAD^2"))
    }

    @Test
    fun `names a remote branch as git does`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")
        git.run(work, "update-ref", "refs/remotes/origin/feature", "feature")
        git.run(work, "branch", "-D", "feature")

        assertEquals(Either.Ok(false), merge(remoteBranch("origin/feature"), fastForward = false))

        assertEquals("Merge remote-tracking branch 'origin/feature'", subject("HEAD"))
    }

    @Test
    fun `merges the branch, not a tag of the same name, which git would take first`(): Unit = runBlocking {
        git.run(work, "tag", "feature")
        commitOnBranch("feature", "feature.txt", "feature\n")

        assertEquals(Either.Ok(false), merge(localBranch("feature"), fastForward = false))

        assertEquals(rev("refs/heads/feature"), rev("HEAD^2"))
        assertEquals("Merge branch 'refs/heads/feature'", subject("HEAD"))
    }

    @Test
    fun `a merge that stops at conflicts has them`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        commit("README.md", "main\n")

        assertEquals(Either.Ok(true), merge(localBranch("feature")))

        assertTrue(File(gitDir, "MERGE_HEAD").isFile)
        assertEquals("README.md", unmergedPaths())
    }

    @Test
    fun `local changes that the merge would overwrite stop it with git's message, and nothing changes`(): Unit =
        runBlocking {
            commitOnBranch("feature", "README.md", "feature\n")
            commit("main.txt", "main\n")
            File(work, "README.md").writeText("local\n")
            val before = rev("HEAD")

            val error = failure(merge(localBranch("feature")))

            assertEquals(2, error.exitCode)
            assertTrue(error.stderr.contains("Your local changes to the following files would be"), error.stderr)
            assertEquals(before, rev("HEAD"))
            assertEquals("local\n", File(work, "README.md").readText())
            assertFalse(File(gitDir, "MERGE_HEAD").exists())
        }

    @Test
    fun `a merge that git refuses while conflicts are left is an error, not one that stopped`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        commitOnBranch("other", "other.txt", "other\n")
        commit("README.md", "main\n")
        assertEquals(Either.Ok(true), merge(localBranch("feature")))

        val error = failure(merge(localBranch("other")))

        assertEquals(128, error.exitCode)
        assertTrue(error.stderr.contains("you have unmerged files"), error.stderr)
    }

    @Test
    fun `follows a file that one side renamed into the other side's changes, as git does`(): Unit = runBlocking {
        File(work, "notes.txt").writeText((1..20).joinToString("\n", postfix = "\n") { "line $it" })
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Notes")
        git.run(work, "switch", "-c", "feature")
        File(work, "notes.txt").writeText(File(work, "notes.txt").readText().replace("line 20", "line twenty"))
        git.run(work, "commit", "-am", "Change notes")
        git.run(work, "switch", "main")
        git.run(work, "mv", "notes.txt", "renamed.txt")
        git.run(work, "commit", "-m", "Rename notes")

        assertEquals(Either.Ok(false), merge(localBranch("feature")))

        assertTrue(File(work, "renamed.txt").readText().endsWith("line twenty\n"))
        assertFalse(File(work, "notes.txt").exists())
    }

    @Test
    fun `runs git's hooks around the merge`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")
        val ran = File(root, "post-merge-ran")
        File(work, ".git/hooks/post-merge").writeExecutable("#!/bin/sh\nprintf '%s\\n' \"\$1\" > '${ran.path}'\n")

        assertEquals(Either.Ok(false), merge(localBranch("feature"), fastForward = false))

        // post-merge gets 0 for a merge that isn't a squash merge
        assertEquals("0\n", ran.readText())
    }

    @Test
    fun `a hook that refuses the merge commit stops it, with the hook's message`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")
        File(work, ".git/hooks/pre-merge-commit")
            .writeExecutable("#!/bin/sh\nprintf '%s\\n' 'No merges today' >&2\nexit 1\n")
        val before = rev("HEAD")

        val error = failure(merge(localBranch("feature"), fastForward = false))

        assertTrue(error.stderr.contains("No merges today"), error.stderr)
        assertEquals(before, rev("HEAD"))
    }

    @Test
    fun `the merge commit has the identity that a file named by includeIf gives`(): Unit = runBlocking {
        File(root, "work.gitconfig").writeText("[user]\n\tname = Work Name\n\temail = work@example.invalid\n")
        globalConfig.appendText("[includeIf \"gitdir:${work.path}/\"]\n\tpath = ${File(root, "work.gitconfig").path}\n")
        commitOnBranch("feature", "feature.txt", "feature\n")

        assertEquals(Either.Ok(false), merge(localBranch("feature"), fastForward = false))

        assertEquals("Work Name <work@example.invalid>", git.run(work, "log", "-1", "--format=%cn <%ce>").trim())
        assertEquals("Work Name <work@example.invalid>", git.run(work, "log", "-1", "--format=%an <%ae>").trim())
    }

    @Test
    fun `signs the merge commit as git does, asking for the SSH key's passphrase in Leaf's dialog`(): Unit =
        runBlocking {
            val sshKeygen = locateProgram("ssh-keygen", currentOs, System.getenv("PATH"))
            assumeTrue(sshKeygen != null, "ssh-keygen is not installed")
            val key = File(root, "keys/id_ed25519").apply { parentFile.mkdirs() }
            run(sshKeygen!!, "-q", "-t", "ed25519", "-N", PASSPHRASE, "-C", "test@example.invalid", "-f", key.path)
            val publicKey = File("${key.path}.pub").readText().trim()
            val allowedSigners = File(root, "allowed_signers").apply {
                writeText("test@example.invalid namespaces=\"git\" $publicKey\n")
            }
            git.run(work, "config", "gpg.format", "ssh")
            git.run(work, "config", "user.signingKey", key.path)
            git.run(work, "config", "commit.gpgSign", "true")
            git.run(work, "config", "gpg.ssh.allowedSignersFile", allowedSigners.path)
            commitOnBranch("feature", "feature.txt", "feature\n")

            val (result, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted(PASSPHRASE) }) {
                merge(localBranch("feature"), fastForward = false)
            }

            assertEquals(Either.Ok(false), result)
            assertEquals(1, dialogs.size, "$dialogs")
            val dialog = dialogs.single()
            assertTrue(dialog is CredentialsRequest.SshCredentialsRequest && !dialog.isRetry, "$dialog")
            val verified = git.run(work, "verify-commit", "HEAD")
            assertTrue(verified.contains("Good \"git\" signature for test@example.invalid"), verified)
        }

    @Test
    fun `cherry-picks a commit, which keeps its author and message`(): Unit = runBlocking {
        git.run(work, "switch", "-c", "feature")
        File(work, "picked.txt").writeText("picked\n")
        git.run(work, "add", ".")
        git.run(
            work,
            mapOf("GIT_AUTHOR_NAME" to "Someone Else", "GIT_AUTHOR_EMAIL" to "else@example.invalid"),
            "commit", "-m", "Pick me",
        )
        git.run(work, "switch", "main")
        commit("main.txt", "main\n")

        assertEquals(Either.Ok(Unit), GitCliCherryPickCommitGitAction(operations)(gitDir, commit("feature")))

        assertEquals("Pick me", subject("HEAD"))
        assertEquals("Someone Else <else@example.invalid>", git.run(work, "log", "-1", "--format=%an <%ae>").trim())
        assertEquals("Leaf Test", git.run(work, "log", "-1", "--format=%cn").trim())
        assertEquals("picked\n", File(work, "picked.txt").readText())
    }

    @Test
    fun `a cherry-pick that stops at conflicts is an error, and leaves them to resolve`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        commit("README.md", "main\n")

        val result = GitCliCherryPickCommitGitAction(operations)(gitDir, commit("feature"))

        assertEquals(Either.Err(GenericError(CHERRY_PICK_STOPPED)), result)
        assertTrue(File(gitDir, "CHERRY_PICK_HEAD").isFile)
        assertEquals("README.md", unmergedPaths())
    }

    @Test
    fun `a commit whose changes are already there stops the cherry-pick, with git's message`(): Unit = runBlocking {
        commitOnBranch("feature", "feature.txt", "feature\n")
        git.run(work, "cherry-pick", "feature")

        val error = failure(GitCliCherryPickCommitGitAction(operations)(gitDir, commit("feature")))

        assertTrue(error.stderr.contains("The previous cherry-pick is now empty"), error.stderr)
        assertTrue(File(gitDir, "CHERRY_PICK_HEAD").isFile)
    }

    @Test
    fun `local changes in the way stop the cherry-pick with git's message`(): Unit = runBlocking {
        commitOnBranch("feature", "README.md", "feature\n")
        File(work, "README.md").writeText("local\n")
        val before = rev("HEAD")

        val error = failure(GitCliCherryPickCommitGitAction(operations)(gitDir, commit("feature")))

        assertTrue(error.stderr.contains("would be overwritten"), error.stderr)
        assertEquals(before, rev("HEAD"))
        assertEquals("local\n", File(work, "README.md").readText())
    }

    @Test
    fun `reverts a commit with git's message`(): Unit = runBlocking {
        commit("added.txt", "added\n", message = "Add a file")
        val reverted = rev("HEAD")

        assertEquals(Either.Ok(Unit), GitCliRevertCommitGitAction(operations)(gitDir, commit("HEAD")))

        assertEquals("Revert \"Add a file\"", subject("HEAD"))
        assertTrue(git.run(work, "log", "-1", "--format=%b").contains("This reverts commit $reverted."))
        assertFalse(File(work, "added.txt").exists())
    }

    @Test
    fun `a revert that stops at conflicts is an error, and leaves them to resolve`(): Unit = runBlocking {
        commit("README.md", "first change\n")
        commit("README.md", "second change\n")

        val result = GitCliRevertCommitGitAction(operations)(gitDir, commit("HEAD~1"))

        assertEquals(Either.Err(GenericError(REVERT_STOPPED)), result)
        assertTrue(File(gitDir, "REVERT_HEAD").isFile)
        assertEquals("README.md", unmergedPaths())
    }

    @Test
    fun `rebases onto a branch`(): Unit = runBlocking {
        commitOnBranch("incoming", "incoming.txt", "incoming\n")
        commit("ours.txt", "ours\n")

        assertEquals(Either.Ok(false), GitCliRebaseBranchGitAction(operations)(gitDir, localBranch("incoming")))

        assertEquals(rev("incoming"), rev("HEAD~1"))
        assertEquals("ours.txt", git.run(work, "show", "--format=", "--name-only", "HEAD").trim())
    }

    @Test
    fun `a rebase that stops at conflicts has them, and Continue goes on with git`(): Unit = runBlocking {
        commitOnBranch("incoming", "README.md", "incoming\n")
        commit("README.md", "ours\n", message = "Our change")

        assertEquals(Either.Ok(true), GitCliRebaseBranchGitAction(operations)(gitDir, localBranch("incoming")))

        assertTrue(isRebaseStartedByGit(File(gitDir)))
        assertEquals("README.md", unmergedPaths())

        File(work, "README.md").writeText("resolved\n")
        git.run(work, "add", "README.md")
        val continueRebase = SelectingContinueRebaseGitAction(GitCliRebase(operations), ContinueRebaseGitAction(jgit))

        assertEquals(Either.Ok(false), continueRebase(gitDir))

        assertFalse(File(gitDir, "rebase-merge").exists())
        assertEquals(rev("incoming"), rev("HEAD~1"))
        assertEquals("Our change", subject("HEAD"))
        assertEquals("resolved\n", File(work, "README.md").readText())
    }

    @Test
    fun `local changes stop the rebase with git's message`(): Unit = runBlocking {
        commitOnBranch("incoming", "incoming.txt", "incoming\n")
        commit("ours.txt", "ours\n")
        File(work, "README.md").writeText("local\n")
        val before = rev("HEAD")

        val error = failure(GitCliRebaseBranchGitAction(operations)(gitDir, localBranch("incoming")))

        assertTrue(error.stderr.contains("cannot rebase: You have unstaged changes"), error.stderr)
        assertEquals(before, rev("HEAD"))
        assertFalse(File(gitDir, "rebase-merge").exists())
    }

    @Test
    fun `Leaf's merge, cherry-pick, revert and rebase run git when it can`(): Unit = runBlocking {
        // JGit in tests reads no includeIf, so only git's commits have this identity
        File(root, "work.gitconfig").writeText("[user]\n\tname = Work Name\n\temail = work@example.invalid\n")
        globalConfig.appendText("[includeIf \"gitdir:${work.path}/\"]\n\tpath = ${File(root, "work.gitconfig").path}\n")
        commitOnBranch("feature", "feature.txt", "feature\n")
        commitOnBranch("picked", "picked.txt", "picked\n")
        commit("main.txt", "main\n")
        val partialClone = testPartialCloneFetch(jgit)

        SelectingMergeBranchGitAction(
            operations, GitCliMergeBranchGitAction(operations), MergeBranchGitAction(jgit, partialClone),
        )(gitDir, localBranch("feature"), fastForward = false).ok()
        assertEquals("Work Name", committer("HEAD"))

        SelectingCherryPickCommitGitAction(
            operations, GitCliCherryPickCommitGitAction(operations), CherryPickCommitGitAction(jgit, partialClone),
        )(gitDir, commit("picked")).ok()
        assertEquals("Work Name", committer("HEAD"))

        SelectingRevertCommitGitAction(
            operations, GitCliRevertCommitGitAction(operations), RevertCommitGitAction(jgit, partialClone),
        )(gitDir, commit("HEAD")).ok()
        assertEquals("Work Name", committer("HEAD"))

        git.run(work, "switch", "picked")
        SelectingRebaseBranchGitAction(
            operations, GitCliRebaseBranchGitAction(operations), RebaseBranchGitAction(jgit, partialClone),
        )(gitDir, localBranch("feature")).ok()
        assertEquals(rev("refs/heads/feature"), rev("HEAD~1"))
        assertEquals("Work Name", committer("HEAD"))
    }

    @Test
    fun `JGit runs them without a usable git, or without the askpass helper`(): Unit = runBlocking {
        val withoutGit = testGitCliOperations(jgit, gitCli, gitExecutablePath = File(root, "missing/git").path)
        val withoutHelper = testGitCliOperations(jgit, gitCli, helper = null)

        assertTrue(operations.useGitCli())
        assertFalse(withoutGit.useGitCli())
        assertFalse(withoutHelper.useGitCli())

        commitOnBranch("feature", "feature.txt", "feature\n")
        val merge = SelectingMergeBranchGitAction(
            withoutGit,
            GitCliMergeBranchGitAction(withoutGit),
            MergeBranchGitAction(jgit, testPartialCloneFetch(jgit)),
        )

        assertEquals(Either.Ok(false), merge(gitDir, localBranch("feature"), fastForward = false))

        // JGit's message, where git's would be "Merge branch 'feature'"
        assertEquals("Merge branch 'feature' into main", subject("HEAD"))
    }

    private suspend fun merge(branch: Branch, fastForward: Boolean = true) =
        GitCliMergeBranchGitAction(operations)(gitDir, branch, fastForward)

    private fun localBranch(name: String) = Branch(rev("refs/heads/$name"), "refs/heads/$name", isLocal = true)

    private fun remoteBranch(name: String) = Branch(rev("refs/remotes/$name"), "refs/remotes/$name", isLocal = false)

    private fun commit(rev: String): Commit = Git.open(work).use { jgit ->
        RevWalk(jgit.repository).use { walk ->
            JGitCommitMapper(JGitIdentityMapper()).toDomain(walk.parseCommit(jgit.repository.resolve(rev)))
        }
    }

    private fun commit(file: String, content: String, message: String = "Change $file") {
        File(work, file).writeText(content)
        git.run(work, "add", file)
        git.run(work, "commit", "-m", message)
    }

    /** Commits [file] on a new [branch] from main, then goes back to main. */
    private fun commitOnBranch(branch: String, file: String, content: String) {
        git.run(work, "switch", "-c", branch)
        commit(file, content)
        git.run(work, "switch", "main")
    }

    private fun rev(rev: String) = git.run(work, "rev-parse", rev).trim()

    private fun subject(rev: String) = git.run(work, "log", "-1", "--format=%s", rev).trim()

    private fun committer(rev: String) = git.run(work, "log", "-1", "--format=%cn", rev).trim()

    private fun <T> Either<T, GitError>.ok(): T = (this as? Either.Ok)?.value ?: error("Expected success: $this")

    private fun unmergedPaths() = git.run(work, "diff", "--name-only", "--diff-filter=U").trim()

    private fun failure(result: Either<*, GitError>): GitCliError.CommandFailed {
        return (result as? Either.Err)?.error as? GitCliError.CommandFailed ?: error("Expected git to fail: $result")
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    }
}
