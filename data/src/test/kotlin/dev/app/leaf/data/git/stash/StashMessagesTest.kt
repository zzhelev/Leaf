// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
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
 * What a `MessageFormat` pattern reads: `{0}` is the first argument, `{b}` isn't one, and `'` quotes until the next `'`.
 * The braces come first, as nothing is read in a quote.
 */
private const val QUOTES_AND_BRACES = "{0} a{b} it's {1} ''"

/**
 * Stashes keep the message they're given. The snapshot command that Gitnuro copied from JGit's stash command took its
 * messages as `MessageFormat` patterns, and both `'` and `{` can be in a branch's name, which the backups' messages
 * give.
 */
@DisabledOnOs(OS.WINDOWS)
class StashMessagesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val repository by lazy { File(tempDir, "repository") }
    private val gitDir by lazy { File(repository, ".git").path }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        git.initRepository(repository)
        File(repository, "README.md").writeText("Changed\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a backup keeps its message as it is`(): Unit = runBlocking {
        val message = "Automatic snapshot stash before merge of \"$QUOTES_AND_BRACES\" to \"main\""

        val backup = CreateSnapshotStashGitAction(JGitCommitMapper(JGitIdentityMapper()), jgit)(
            gitDir,
            message,
            includeUntracked = true,
        )

        assertInstanceOf(Either.Ok::class.java, backup, backup.toString())
        assertEquals(message, stashListEntry())
        assertEquals(message, message("refs/stash"))
    }

    @Test
    fun `a backup without a message names the branch, the commit and its subject, as before`() {
        git.run(repository, "switch", "-c", "it's{0}")
        git.run(repository, "commit", "--allow-empty", "-m", "Subject {1} it's")
        File(repository, "untracked.txt").writeText("New\n")
        val head = git.run(repository, "rev-parse", "--short=7", "HEAD").trim()

        Git.open(File(gitDir)).use { SnapshotStashCreateCommand(it.repository, includeUntracked = true).call() }

        val on = "on it's{0}: $head Subject {1} it's"
        assertEquals("Snapshot WIP $on", message("refs/stash"))
        assertEquals("index $on", message("refs/stash^2"))
        assertEquals("untracked files $on", message("refs/stash^3"))
    }

    /** The newest entry of the stash list, which `git stash list` shows. */
    private fun stashListEntry() = git.run(repository, "stash", "list", "-1", "--format=%gs").trimEnd('\n')

    private fun message(revision: String) = git.run(repository, "log", "-1", "--format=%B", revision).trimEnd('\n')
}
