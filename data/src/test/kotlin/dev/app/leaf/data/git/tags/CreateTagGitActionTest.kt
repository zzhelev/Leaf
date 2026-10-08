// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.signers.GpgProgramSigner
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val SIGNATURE = "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQRK\n-----END PGP SIGNATURE-----\n"

/** Signs tags as git does, with the signer that `App.start` registers for `gpg.format`. */
@DisabledOnOs(OS.WINDOWS)
class CreateTagGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val createTag = CreateTagGitAction(jgit)

    /** Records its arguments next to itself and signs like gpg. */
    private val gpg by lazy {
        File(tempDir, "bin/gpg").writeExecutable(
            """
            |#!/bin/sh
            |printf '%s\n' "${'$'}@" > "${'$'}(dirname "${'$'}0")/args"
            |cat > /dev/null
            |echo '[GNUPG:] SIG_CREATED D 22 10 00 1791381795 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A' >&2
            |printf '%s' '$SIGNATURE'
            |""".trimMargin()
        )
    }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        Signers.set(GpgConfig.GpgFormat.OPENPGP, GpgProgramSigner(ProcessRunner(), LoginShellEnvironment { emptyMap() }))
    }

    @AfterEach
    fun tearDown() {
        Signers.set(GpgConfig.GpgFormat.OPENPGP, null)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `signs with gpg when tag gpgSign is set and gpg format is not, with the tagger's identity`(): Unit =
        runBlocking {
            val repository = git.initRepository(File(tempDir, "repo"))
            git.run(repository, "config", "tag.gpgSign", "true")
            git.run(repository, "config", "gpg.program", gpg.path)
            git.run(repository, "config", "user.name", "Leaf Tagger")
            git.run(repository, "config", "user.email", "tagger@example.com")

            assertInstanceOf(Either.Ok::class.java, createTag(gitDir(repository), "v1", head(repository)))

            assertEquals(SIGNATURE.trim(), tagSignature(repository, "v1")?.trim())
            assertEquals("Leaf Tagger <tagger@example.com>", File(gpg.parentFile, "args").readLines()[2])
        }

    @Test
    fun `signs with user signingKey`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "tag.gpgSign", "true")
        git.run(repository, "config", "gpg.format", "openpgp")
        git.run(repository, "config", "gpg.program", gpg.path)
        git.run(repository, "config", "user.signingKey", "4AE4445EFE49B9DF")

        assertInstanceOf(Either.Ok::class.java, createTag(gitDir(repository), "v1", head(repository)))

        assertEquals("4AE4445EFE49B9DF", File(gpg.parentFile, "args").readLines()[2])
    }

    @Test
    fun `does not sign without tag gpgSign`(): Unit = runBlocking {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "config", "gpg.program", gpg.path)
        git.run(repository, "config", "user.signingKey", "4AE4445EFE49B9DF")

        assertInstanceOf(Either.Ok::class.java, createTag(gitDir(repository), "v1", head(repository)))

        assertNull(tagSignature(repository, "v1"))
        assertFalse(File(gpg.parentFile, "args").exists())
    }

    private fun gitDir(repository: File) = File(repository, ".git").path

    private fun head(repository: File): Commit {
        val hash = git.run(repository, "rev-parse", "HEAD").trim()
        val identity = Identity("Leaf Test", "test@example.invalid")

        return Commit(hash, "Initial commit", identity, identity, 0, emptyList())
    }

    private fun tagSignature(repository: File, name: String): String? {
        return Git.open(repository).use { git ->
            RevWalk(git.repository).use { walk ->
                val tag = walk.parseTag(git.repository.resolve("refs/tags/$name"))
                tag.rawGpgSignature?.decodeToString()
            }
        }
    }
}
