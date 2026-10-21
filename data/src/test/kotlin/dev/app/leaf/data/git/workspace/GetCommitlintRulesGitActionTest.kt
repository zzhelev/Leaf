// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.EnumRule
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Finding a worktree's commitlint config as commitlint does, in the root of its working tree (fork-only). */
class GetCommitlintRulesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val action by lazy { GetCommitlintRulesGitAction(testJGit()) }

    @BeforeEach
    fun isolate() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restore() {
        SystemReader.setInstance(originalReader)
    }

    private fun rulesOf(gitDir: File) = runBlocking { (action(gitDir.path) as Either.Ok).value }

    private fun write(name: String, text: String, root: File = repository) = File(root, name).writeText(text)

    @Test
    fun `none without a config`() {
        write("package.json", """{"name": "app"}""")

        assertEquals(null, rulesOf(File(repository, ".git")))
    }

    @Test
    fun `the first file in commitlint's order wins, and package json only with its key`() {
        write("commitlint.config.js", "module.exports = { rules: { 'type-enum': [2, 'always', ['js']] } }")
        assertEquals("commitlint.config.js", rulesOf(File(repository, ".git"))?.file)

        write(".commitlintrc.yml", "rules:\n  type-enum: [2, always, [yaml]]\n")
        assertEquals(
            CommitlintRules(".commitlintrc.yml", types = EnumRule(setOf("yaml"), allows = true)),
            rulesOf(File(repository, ".git")),
        )

        write("package.json", """{"name": "app"}""")
        assertEquals(".commitlintrc.yml", rulesOf(File(repository, ".git"))?.file, "No commitlint key: skipped")

        write("package.json", """{"name": "app", "commitlint": {"extends": ["@commitlint/config-conventional"]}}""")
        assertEquals("package.json", rulesOf(File(repository, ".git"))?.file)
    }

    @Test
    fun `a linked worktree reads its own working tree, and a bare repository has none`() {
        write(".commitlintrc.json", """{"rules": {"type-enum": [2, "always", ["main"]]}}""")
        git.run(repository, "add", ".commitlintrc.json")
        git.run(repository, "commit", "-m", "Add the config")
        git.run(repository, "worktree", "add", "-q", "-b", "agent", File(tempDir, "agent").path)
        write(".commitlintrc.json", """{"rules": {"type-enum": [2, "always", ["agent"]]}}""", root = File(tempDir, "agent"))

        val linkedGitDir = File(repository, ".git/worktrees/agent")
        assertEquals(setOf("agent"), rulesOf(linkedGitDir)?.types?.values)
        assertEquals(setOf("main"), rulesOf(File(repository, ".git"))?.types?.values)

        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "-q", "--bare", repository.path, bare.path)
        assertEquals(null, rulesOf(bare))
    }
}
