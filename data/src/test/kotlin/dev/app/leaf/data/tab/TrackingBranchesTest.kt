// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.models.TrackingBranch
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A tab's local branches carry the branch that each one tracks, which upstream's 2.0 code reads (`Branch.trackingBranch`). */
class TrackingBranchesTest {
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

    private val main by lazy { File(tempDir, "main") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(main)
        git.run(main, "branch", "feature")
        git.run(main, "branch", "renamed")
        git.run(main, "config", "branch.feature.remote", "origin")
        git.run(main, "config", "branch.feature.merge", "refs/heads/feature")
        git.run(main, "config", "branch.renamed.remote", "upstream")
        git.run(main, "config", "branch.renamed.merge", "refs/heads/other-name")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `each local branch has the remote branch it tracks, and none without an upstream`(): Unit = runBlocking {
        tab.openRepository(main.path)

        val branches = tab.awaitLoaded(tab.repositoryData.localBranches) { it.size == 3 }.associateBy { it.simpleName }

        assertNull(branches.getValue("main").trackingBranch)
        assertEquals(TrackingBranch("origin", "feature"), branches.getValue("feature").trackingBranch)
        assertTrue(branches.getValue("feature").matchesTrackingBranchName)
        assertEquals(TrackingBranch("upstream", "other-name"), branches.getValue("renamed").trackingBranch)
        assertFalse(branches.getValue("renamed").matchesTrackingBranchName)
    }

    @Test
    fun `a branch refresh reads an upstream set since the last one`(): Unit = runBlocking {
        tab.openRepository(main.path)
        tab.awaitLoaded(tab.repositoryData.localBranches) { it.size == 3 }

        git.run(main, "config", "branch.main.remote", "origin")
        git.run(main, "config", "branch.main.merge", "refs/heads/main")
        tab.refreshData(DataToRefresh.BRANCHES).join()

        val branches = tab.awaitLoaded(tab.repositoryData.localBranches) { branches ->
            branches.any { it.simpleName == "main" && it.trackingBranch != null }
        }

        assertEquals(TrackingBranch("origin", "main"), branches.single { it.simpleName == "main" }.trackingBranch)
    }
}
