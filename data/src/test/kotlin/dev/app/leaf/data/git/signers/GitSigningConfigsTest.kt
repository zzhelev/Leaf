// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.signers

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.credentials.parseConfigList
import dev.app.leaf.data.git.isolatedGitCli
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.DoCommitGitAction
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PGP_SIGNATURE = "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQRK\n-----END PGP SIGNATURE-----\n"
private const val SSH_SIGNATURE = "-----BEGIN SSH SIGNATURE-----\nU1NIU0lHAAAAAQ==\n-----END SSH SIGNATURE-----\n"
private const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOp4 work@example.com"

/** Leaf's commits sign as git's config says, with the files that `includeIf` names, which JGit skips. */
@DisabledOnOs(OS.WINDOWS)
class GitSigningConfigsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }
    private val gitCli by lazy { isolatedGitCli(globalConfig) }
    private val jgit = testJGit()

    /** Records its arguments next to itself and signs like gpg. */
    private val gpg by lazy {
        File(root, "bin/gpg").writeExecutable(
            """
            |#!/bin/sh
            |printf '%s\n' "${'$'}@" > "${'$'}(dirname "${'$'}0")/args"
            |cat > /dev/null
            |echo '[GNUPG:] SIG_CREATED D 22 10 00 1791381795 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A' >&2
            |printf '%s' '$PGP_SIGNATURE'
            |""".trimMargin()
        )
    }

    /** Records its arguments next to itself and signs like `ssh-keygen -Y sign`: the data file is the last one. */
    private val sshKeygen by lazy {
        File(root, "bin/work-ssh-keygen").writeExecutable(
            """
            |#!/bin/sh
            |for argument in "${'$'}@"; do
            |  last=${'$'}argument
            |  printf '%s\n' "${'$'}argument"
            |done > "${'$'}(dirname "${'$'}0")/ssh-args"
            |printf '%s' '$SSH_SIGNATURE' > "${'$'}last.sig"
            |""".trimMargin()
        )
    }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
        val gpgSigner = GpgProgramSigner(ProcessRunner(), LoginShellEnvironment { emptyMap() })
        Signers.set(GpgConfig.GpgFormat.OPENPGP, gpgSigner)
        Signers.set(GpgConfig.GpgFormat.SSH, sshSigner())
    }

    @AfterEach
    fun tearDown() {
        Signers.set(GpgConfig.GpgFormat.OPENPGP, null)
        Signers.set(GpgConfig.GpgFormat.SSH, null)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `JGit's config has every value as git reads it`() {
        val included = File(root, "included.gitconfig")
        included.writeText("[included]\n\tkey = from the included file\n")
        val file = File(root, "values.gitconfig")
        file.writeText(
            """
            |[test]
            |	quoted = "a \"b\" c"
            |	backslash = a\\b
            |	tab = "a\tb"
            |	lines = "one\ntwo"
            |	backspace = "a\bb"
            |	spaces = "  padded  "
            |	comment = "a # b ; c"
            |	multi = one
            |	multi = two
            |	empty =
            |	bare
            |[test "Sub.With.Dots"]
            |	key = in a subsection
            |[test "quote\"and\\backslash"]
            |	key = in another
            |[include]
            |	path = ${included.path}
            |""".trimMargin()
        )
        val keys = listOf(
            "test.quoted", "test.backslash", "test.tab", "test.lines", "test.backspace", "test.spaces",
            "test.comment", "test.multi", "test.empty", "test.Sub.With.Dots.key", "test.quote\"and\\backslash.key",
            "included.key",
        )

        // git reads the files that include names only when asked, with --file
        val config = configOf(parseConfigList(git(root, "config", "--includes", "--file", file.path, "--list", "-z")))
        val readByJGit = FileBasedConfig(file, FS.DETECTED).apply { load() }

        for (key in keys) {
            val section = key.substringBefore('.')
            val name = key.substringAfterLast('.')
            val subsection = key.removePrefix("$section.").removeSuffix(".$name").takeIf { it != name }
            val values = git(root, "config", "--includes", "--file", file.path, "--get-all", "-z", key)
                .split('\u0000')
                .dropLast(1)
            val read = config.getStringList(section, subsection, name).toList()

            // JGit reads an empty value as null, from its own files too
            assertEquals(values.map { it.ifEmpty { null } }, read, key)
            assertEquals(readByJGit.getStringList(section, subsection, name).toList(), read, key)
        }
        assertEquals("true", git(root, "config", "--file", file.path, "--type=bool", "--get", "test.bare").trim())
        assertTrue(config.getBoolean("test", "bare", false))
        assertEquals(emptySet<String>(), config.getSubsections("include") + config.getNames("include"))
    }

    @Test
    fun `commits are signed as a file that includeIf names says`(): Unit = runBlocking {
        val repository = workRepository(
            "[commit]\n\tgpgSign = true\n[gpg]\n\tprogram = ${gpg.path}\n[user]\n\tsigningKey = 4AE4445EFE49B9DF\n"
        )

        val result = doCommit()(File(repository, ".git").path, "Signed", false, null)

        assertInstanceOf(Either.Ok::class.java, result)
        assertTrue(git(repository, "cat-file", "commit", "HEAD").contains("gpgsig $PGP_SIGNATURE".lines().first()))
        assertEquals("4AE4445EFE49B9DF", File(gpg.parentFile, "args").readLines()[2])
    }

    @Test
    fun `commits are signed with the ssh program and key that a file that includeIf names gives`(): Unit =
        runBlocking {
            val repository = workRepository(
                """
                |[commit]
                |	gpgSign = true
                |[gpg]
                |	format = ssh
                |[gpg "ssh"]
                |	program = ${sshKeygen.path}
                |[user]
                |	signingKey = key::$PUBLIC_KEY
                |""".trimMargin()
            )

            val result = doCommit()(File(repository, ".git").path, "Signed", false, null)

            assertInstanceOf(Either.Ok::class.java, result)
            assertTrue(git(repository, "cat-file", "commit", "HEAD").contains("gpgsig -----BEGIN SSH SIGNATURE-----"))
            val arguments = File(sshKeygen.parentFile, "ssh-args").readLines()
            assertEquals(listOf("-Y", "sign", "-n", "git", "-f"), arguments.take(5))
            assertTrue("-U" in arguments, "$arguments")
        }

    /**
     * A repository under `work/`, whose identity and [signing] settings come from a file that the user's config
     * includes only there.
     */
    private fun workRepository(signing: String): File {
        val workConfig = File(root, "work.gitconfig")
        workConfig.writeText("[user]\n\tname = Work Name\n\temail = work@example.com\n$signing")
        globalConfig.writeText(
            "[user]\n\tname = Personal Name\n\temail = personal@example.com\n" +
                "[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${workConfig.path}\n"
        )

        val repository = File(root, "work/repo").apply { mkdirs() }
        git(repository, "init", "-q", "-b", "main")
        File(repository, "a.txt").writeText("a\n")
        git(repository, "add", "a.txt")

        return repository
    }

    private fun doCommit() = DoCommitGitAction(
        GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
        JGitCommitMapper(JGitIdentityMapper()),
        JGitIdentityMapper(),
        jgit,
        GitIdentities(gitCli),
        GitSigningConfigs(gitCli),
    )

    private fun sshSigner(): SshProgramSigner {
        val processRunner = ProcessRunner()
        val askpass = AskpassProcessRunner(
            processRunner,
            AskpassHelper { null },
            CredentialsStateManager(),
            CredentialsCacheRepository(),
        )

        return SshProgramSigner(askpass, processRunner, LoginShellEnvironment { emptyMap() })
    }

    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
