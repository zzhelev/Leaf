// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.config.IncludedConfig
import dev.app.leaf.data.git.config.IncludedConfigSource
import dev.app.leaf.data.git.lfs.GitLfsSetup
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.time.Duration.Companion.seconds

/**
 * A caller of [JGit.provide] that is cancelled while the repository's config is refreshed: while git reads what
 * `includeIf` names, or while `git lfs version` runs ([GitLfsSetup]). The cancellation reaches the caller, its block
 * doesn't run, and nothing is logged as an error. Both catches took the cancellation for a failure: they logged
 * "StandaloneCoroutine was cancelled", and `provide` went on to run the block.
 */
class CancelledRefreshTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    private val gitDir get() = File(tempDir, "repo/.git").path

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        Git.init().setDirectory(File(tempDir, "repo")).setInitialBranch("main").call().close()
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a caller cancelled while git reads what includeIf names stops there, and logs nothing`(): Unit = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val source = object : IncludedConfigSource {
            override suspend fun read(repository: Repository): IncludedConfig {
                reading.complete(Unit)
                awaitCancellation()
            }
        }

        val outcome = cancelledWhile(testJGit(includedConfig = source)) { reading.await() }

        assertEquals(Outcome(cancellationReachedCaller = true, blockRan = false, errors = emptyList()), outcome)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a caller cancelled while git-lfs is asked stops there, logs nothing, and nothing is kept`(): Unit =
        runBlocking {
            setGitLfsFilters()
            val runs = File(tempDir, "git-lfs-runs.log")
            val gitLfs = File(tempDir, "bin/git-lfs")
            // A git-lfs that is slow to answer
            gitLfs.writeExecutable("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '${runs.path}'\nexec sleep 30\n")
            val gitCli = testGitCli(
                mapOf(
                    "PATH" to "${gitLfs.parentFile.absolutePath}:/usr/bin:/bin",
                    "HOME" to tempDir.path,
                    "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.path,
                    "GIT_CONFIG_NOSYSTEM" to "1",
                )
            )
            val jgit = testJGit(includedConfig = NoIncludedConfig, gitLfsSetup = GitLfsSetup(gitCli))

            val outcome = cancelledWhile(jgit) {
                while (!runs.exists()) delay(20)
            }

            assertEquals(Outcome(cancellationReachedCaller = true, blockRan = false, errors = emptyList()), outcome)

            // The cancelled check isn't kept as "git-lfs doesn't work": the next caller asks git-lfs again
            gitLfs.writeExecutable("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '${runs.path}'\nprintf 'git-lfs/3.8.0\\n'\n")
            val usesBuiltinLfs = jgit.provide(gitDir) { git ->
                git.repository.config.getBoolean(
                    ConfigConstants.CONFIG_FILTER_SECTION,
                    "lfs",
                    ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
                    false,
                )
            }

            assertEquals(Either.Ok(false), usesBuiltinLfs)
            assertEquals(listOf("version", "version"), runs.readLines())
        }

    private data class Outcome(val cancellationReachedCaller: Boolean, val blockRan: Boolean, val errors: List<String>)

    /**
     * Runs [JGit.provide] in a coroutine and cancels it once [whenToCancel] returns: whether the cancellation came out
     * of `provide`, whether the caller's block ran, and what was logged as an error meanwhile.
     */
    private suspend fun cancelledWhile(jgit: JGit, whenToCancel: suspend () -> Unit): Outcome {
        var cancellationReachedCaller = false
        var blockRan = false
        val printed = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(printed, true))

        try {
            withTimeout(20.seconds) {
                val job = launch(Dispatchers.IO) {
                    try {
                        jgit.provide(gitDir) { blockRan = true }
                    } catch (e: CancellationException) {
                        cancellationReachedCaller = true
                        throw e
                    }
                }

                whenToCancel()
                job.cancelAndJoin()
            }
        } finally {
            System.setOut(originalOut)
        }

        val errors = printed.toString().lines().filter { it.startsWith("[ERROR]") }

        return Outcome(cancellationReachedCaller, blockRan, errors)
    }

    /** The filters that `git lfs install` writes, so that [GitLfsSetup] asks git-lfs whether it works. */
    private fun setGitLfsFilters() {
        Git.open(File(gitDir)).use { git ->
            val config = git.repository.config
            config.setString(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", "clean", "git-lfs clean -- %f")
            config.setString(ConfigConstants.CONFIG_FILTER_SECTION, "lfs", "smudge", "git-lfs smudge -- %f")
            config.save()
        }
    }
}

/** Nothing named through `includeIf`: JGit opens an [IncludedConfigRepository], whose LFS switch [JGit] sets. */
private object NoIncludedConfig : IncludedConfigSource {
    override suspend fun read(repository: Repository) = IncludedConfig(entries = emptyList(), files = emptyList())
}
