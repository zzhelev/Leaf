// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.repositories.DataState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fetch all in a tab whose remote merged a branch on its website, deleted it and tagged the merge, as Bitbucket and
 * GitHub do with a pull request: the tab's remote branches and tags, which the log's labels and the side panel show,
 * are git's afterwards, not only its log. Skipped without the askpass helper, as git fetches.
 */
@DisabledOnOs(OS.WINDOWS)
class FetchingAllRemotesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val bare by lazy { File(tempDir, "origin.git") }
    private val work by lazy { File(tempDir, "work") }
    private val server by lazy { File(tempDir, "server") }

    @BeforeEach
    fun createRepositories() {
        assumeTrue(builtAskpassHelper() != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        git.run(tempDir, "init", "--bare", bare.path)
        val seed = git.initRepository(File(tempDir, "seed"))
        git.run(seed, "push", bare.path, "main")
        git.run(tempDir, "clone", bare.path, work.path)

        git.run(work, "switch", "-c", "feature")
        File(work, "feature.txt").writeText("feature\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", "Feature")
        git.run(work, "push", "-u", "origin", "feature")

        // Stands in for the server's website, which merges the pull request
        git.run(tempDir, "clone", bare.path, server.path)
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `fetch all shows where the remote's branches are now, which ones went, and the tags it brought`(): Unit =
        runBlocking {
            open()
            val merge = mergeFeatureOnServer()

            tab.fetchAll()

            tab.awaitLoaded(tab.repositoryData.log) { log -> log.containsKey(merge) }
            assertEquals(gitRemoteBranches(), leafRemoteBranches())
            assertEquals(mapOf("refs/remotes/origin/main" to merge), leafRemoteBranches().filterKeys { "main" in it })
            assertEquals(mapOf("refs/tags/3.4.1" to merge), leafTags())
        }

    @Test
    fun `a remote that fails to fetch doesn't keep the others' branches from moving`(): Unit = runBlocking {
        git.run(work, "remote", "add", "broken", File(tempDir, "missing.git").path)
        open()
        val merge = mergeFeatureOnServer()

        tab.fetchAll()

        tab.awaitLoaded(tab.repositoryData.log) { log -> log.containsKey(merge) }
        assertEquals(gitRemoteBranches(), leafRemoteBranches())
        assertEquals(mapOf("refs/tags/3.4.1" to merge), leafTags())
    }

    private suspend fun open() {
        tab.openRepository(work.path)
        tab.awaitLoaded(tab.repositoryData.remotes) { remotes ->
            remotes.flatMap { it.branchesList }.any { it.simpleName == "feature" }
        }
        tab.awaitLoaded(tab.repositoryData.log)
    }

    /** Merges `feature` into `main` on the server, deletes `feature` there and tags the merge. Returns the merge. */
    private fun mergeFeatureOnServer(): String {
        git.run(server, "merge", "--no-ff", "-m", "Merged in feature (pull request #1)", "origin/feature")
        git.run(server, "tag", "-a", "3.4.1", "-m", "3.4.1")
        git.run(server, "push", "origin", "main", "3.4.1")
        git.run(server, "push", "origin", "--delete", "feature")

        return git.run(server, "rev-parse", "HEAD").trim()
    }

    /** The tab's remote branches by full name, at their commits, as the log's labels take them. */
    private suspend fun leafRemoteBranches(): Map<String, String> {
        val remotes = (tab.repositoryData.remotes.first() as DataState.Loaded).data

        return remotes.flatMap { it.branchesList }.filter { it.simpleName != "HEAD" }.associate { it.name to it.hash }
    }

    private suspend fun leafTags(): Map<String, String> =
        (tab.repositoryData.tags.first() as DataState.Loaded).data.associate { it.name to it.commitHash }

    private fun gitRemoteBranches(): Map<String, String> =
        git.run(work, "for-each-ref", "--format=%(refname) %(objectname)", "refs/remotes")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("refs/remotes/origin/HEAD ") }
            .associate { line -> line.substringBefore(" ") to line.substringAfter(" ") }
}
