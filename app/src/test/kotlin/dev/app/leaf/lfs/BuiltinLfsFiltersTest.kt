// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.lfs

import com.sun.net.httpserver.HttpServer
import dev.app.leaf.data.git.IncludedConfigRepository
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.AuthenticateLfsServerWithSshGitAction
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.FetchLfsObjectGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.openWithIncludedConfig
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.attributes.FilterCommandRegistry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.LfsFactory
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.inject.Provider

private const val V1 = "real content of the photo, version 1"
private const val V2 = "real content of the photo, version 2"

/**
 * JGit with Leaf's own LFS filters (`AppLfsFactory`), as when git-lfs isn't set up: checkouts write the objects from
 * the store in the common git dir, staging stores pointers, and a working tree of pointer files stays clean.
 */
@DisabledOnOs(OS.WINDOWS)
class BuiltinLfsFiltersTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val originalLfsFactory: LfsFactory = LfsFactory.getInstance()

    /** An LFS server that has no object: it answers 404 to all. */
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
    }

    private val repo get() = File(tempDir, "repo")

    @BeforeEach
    fun setUp() {
        val configDir = File(tempDir, "config")
        SystemReader.setInstance(object : SystemReader.Delegate(originalReader) {
            override fun openUserConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "user.gitconfig"), fs)

            override fun openSystemConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

            override fun openJGitConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "jgit.config"), fs)
        })
        LfsFactory.setInstance(appLfsFactory())
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        FilterCommandRegistry.unregister("jgit://builtin/lfs/smudge")
        FilterCommandRegistry.unregister("jgit://builtin/lfs/clean")
        LfsFactory.setInstance(originalLfsFactory)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a working tree of pointer files stays clean when JGit compares their content`() {
        repositoryWithPhoto()

        withBuiltinLfs { git ->
            touch(File(repo, "photo.bin"))

            assertTrue(git.status().call().isClean)
        }
    }

    @Test
    fun `the diff of an edited file shows the stored version against the working tree's text`() {
        repositoryWithPhoto()

        withBuiltinLfs { git ->
            git.checkout().setName("feature").call()
            File(repo, "photo.bin").writeText("$V2, edited")
            val entry = git.diff().setShowNameAndStatusOnly(true).call().single()

            // As the diff pane reads an unstaged file
            val content = GetDiffContentGitAction(RawFileManager(mockk(relaxed = true)))(
                git.repository,
                entry,
                DirCacheIterator(git.repository.readDirCache()),
                FileTreeIterator(git.repository),
                DiffWhitespaceMode.SHOW_ALL,
            )

            assertEquals(V2, (content.rawOld as EntryContent.Text).rawText.getString(0))
            assertEquals("$V2, edited", (content.rawNew as EntryContent.Text).rawText.getString(0))
        }
    }

    @Test
    fun `in a linked worktree, a checkout writes the object from the common store, and staging stores a pointer`() {
        repositoryWithPhoto()
        val worktree = File(tempDir, "wt")
        runGit(repo, "worktree", "add", "-q", "-b", "agent", worktree.path)

        withBuiltinLfs(File(repo, ".git/worktrees/wt")) { git ->
            git.checkout().setName("feature").call()
            assertEquals(V2, File(worktree, "photo.bin").readText())

            touch(File(worktree, "photo.bin"))
            assertTrue(git.status().call().isClean)

            val edited = "edited in the worktree"
            File(worktree, "photo.bin").writeText(edited)
            git.add().addFilepattern("photo.bin").call()

            val entry = git.repository.readDirCache().getEntry("photo.bin")
            val staged = git.repository.open(entry.objectId).bytes.decodeToString()
            assertEquals(lfsPointerText(oid(edited), edited.length.toLong()), staged)
            assertEquals(edited, File(repo, ".git/lfs/objects/${objectPath(edited)}").readText())
            assertFalse(File(repo, ".git/worktrees/wt/lfs").exists())
        }
    }

    @Test
    fun `a checkout writes a pointer that a merge's markers broke as it is, as git-lfs does`() {
        repositoryWithPhoto()
        val conflicted = "version https://git-lfs.github.com/spec/v1\n<<<<<<< HEAD\n" +
            "oid sha256:${sha256(V1)}\nsize ${V1.length}\n=======\n" +
            "oid sha256:${sha256(V2)}\nsize ${V2.length}\n>>>>>>> feature\n"

        Git.open(repo).use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.checkout().setCreateBranch(true).setName("conflicted").call()
            File(repo, "photo.bin").writeText(conflicted)
            git.add().addFilepattern("photo.bin").call()
            git.commit().setMessage("Markers").setAuthor(identity).setCommitter(identity).call()
            git.checkout().setName("main").call()
        }

        withBuiltinLfs { git ->
            git.checkout().setName("conflicted").call()

            assertEquals(conflicted, File(repo, "photo.bin").readText())
        }
    }

    @Test
    fun `a checkout writes a pointer with extension lines as it is, as their programs aren't run`() {
        repositoryWithPhoto()
        val withExtension = "version https://git-lfs.github.com/spec/v1\next-0-foo sha256:${sha256(V1)}\n" +
            "oid sha256:${sha256(V1)}\nsize ${V1.length}\n"

        Git.open(repo).use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.checkout().setCreateBranch(true).setName("extension").call()
            File(repo, "photo.bin").writeText(withExtension)
            git.add().addFilepattern("photo.bin").call()
            git.commit().setMessage("Extension").setAuthor(identity).setCommitter(identity).call()
            git.checkout().setName("main").call()
        }

        withBuiltinLfs { git ->
            git.checkout().setName("extension").call()

            assertEquals(withExtension, File(repo, "photo.bin").readText())
        }
    }

    @Test
    fun `a checkout writes a file that isn't a pointer as it is, however long`() {
        repositoryWithPhoto()
        val notAPointer = "a file committed without the LFS filter\n".repeat(200)

        Git.open(repo).use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.checkout().setCreateBranch(true).setName("raw").call()
            File(repo, "photo.bin").writeText(notAPointer)
            git.add().addFilepattern("photo.bin").call()
            git.commit().setMessage("Raw").setAuthor(identity).setCommitter(identity).call()
            git.checkout().setName("main").call()
        }

        withBuiltinLfs { git ->
            git.checkout().setName("raw").call()

            assertEquals(notAPointer, File(repo, "photo.bin").readText())
        }
    }

    @Test
    fun `a checkout fails when an object can't be downloaded, as git-lfs's does`() {
        repositoryWithPhoto(storesV2 = false)

        withBuiltinLfs { git ->
            assertThrows<Exception> { git.checkout().setName("feature").call() }
        }
    }

    @Test
    fun `with lfs skipdownloaderrors, a checkout writes the pointer of an object it can't download`() {
        repositoryWithPhoto(storesV2 = false)

        withBuiltinLfs { git ->
            git.repository.config.setBoolean("lfs", null, "skipdownloaderrors", true)
            git.repository.config.save()

            git.checkout().setName("feature").call()

            assertEquals(lfsPointerText(oid(V2), V2.length.toLong()), File(repo, "photo.bin").readText())
        }
    }

    /**
     * A repository on `main` with `photo.bin` tracked by LFS (V1) as a pointer file, and a branch `feature` that
     * changes it to V2. The store has V1, and V2 unless [storesV2] is false. Its remote's LFS server has nothing.
     */
    private fun repositoryWithPhoto(storesV2: Boolean = true) {
        Git.init().setDirectory(repo).setInitialBranch("main").call().use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            File(repo, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(repo, "photo.bin").writeText(lfsPointerText(oid(V1), V1.length.toLong()))
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Photo").setAuthor(identity).setCommitter(identity).call()

            git.checkout().setCreateBranch(true).setName("feature").call()
            File(repo, "photo.bin").writeText(lfsPointerText(oid(V2), V2.length.toLong()))
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Photo, version 2").setAuthor(identity).setCommitter(identity).call()
            git.checkout().setName("main").call()

            git.remoteAdd().setName("origin").setUri(URIish("http://127.0.0.1:${server.address.port}/repo")).call()
        }

        store(V1)
        if (storesV2) {
            store(V2)
        }
    }

    private fun store(content: String) {
        File(repo, ".git/lfs/objects/${objectPath(content)}").apply { parentFile.mkdirs() }.writeText(content)
    }

    /** Opens [gitDir] as Leaf's JGit does, with Leaf's LFS filters turned on as when git-lfs isn't set up. */
    private fun withBuiltinLfs(gitDir: File = File(repo, ".git"), block: (Git) -> Unit) {
        openWithIncludedConfig(gitDir, FS.DETECTED).use { git ->
            (git.repository as IncludedConfigRepository).useBuiltinLfs(true)
            block(git)
        }
    }

    private fun appLfsFactory(): AppLfsFactory {
        val shellEnvironment = LoginShellEnvironment(ProcessRunner())
        val jgit = JGit(Provider { error("Only used on Windows") }, shellEnvironment)
        val settings = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(File(tempDir, "no-git").path)
        }
        val withoutGit = GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            AppSettingsService(settings),
            shellEnvironment,
        )
        val lfsRepository = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))
        val credentialsStateManager = CredentialsStateManager()
        // Not asked: the server answers 404, not 401
        val credentials = ProvideLfsCredentialsGitAction(
            CredentialsCacheRepository(),
            credentialsStateManager,
            CredentialHelpers(ShellManager(), mockk(relaxed = true), shellEnvironment, withoutGit),
            AppSettingsService(settings),
        )
        val getLfsUrl = GetLfsUrlGitAction(
            GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
            GetLfsUrlSettingGitAction(withoutGit),
        )
        val fetch = FetchLfsObjectGitAction(
            GetLfsObjectsGitAction(lfsRepository, credentials),
            AuthenticateLfsServerWithSshGitAction(
                AskpassProcessRunner(
                    ProcessRunner(),
                    AskpassHelper { null },
                    credentialsStateManager,
                    CredentialsCacheRepository(),
                ),
                withoutGit,
                shellEnvironment,
            ),
            DownloadLfsObjectGitAction(lfsRepository, credentials),
        )

        return AppLfsFactory(
            lfsPrePushHookFactory = object : LfsPrePushHookFactory {
                override fun create(
                    repository: org.eclipse.jgit.lib.Repository,
                    outputStream: java.io.PrintStream?,
                    errorStream: java.io.PrintStream?,
                ) = error("Not pushed here")
            },
            lfsSmudgeFilterFactory = object : LfsSmudgeFilterFactory {
                override fun create(
                    repository: org.eclipse.jgit.lib.Repository,
                    input: java.io.InputStream,
                    output: java.io.OutputStream,
                ) = LfsSmudgeFilter(repository, input, output, getLfsUrl, fetch, shellEnvironment)
            },
            lfsCleanFilterFactory = object : LfsCleanFilterFactory {
                override fun create(
                    repository: org.eclipse.jgit.lib.Repository,
                    input: java.io.InputStream,
                    output: java.io.OutputStream,
                ) = LfsCleanFilter(repository, input, output)
            },
        )
    }

    private fun runGit(folder: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(folder)
            .apply {
                environment()["HOME"] = tempDir.path
                environment()["GIT_CONFIG_GLOBAL"] = File(tempDir, "empty.gitconfig").apply { createNewFile() }.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()

        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    /** Moves the file's time on, so that JGit compares its content with the index. */
    private fun touch(file: File) {
        file.setLastModified(file.lastModified() + 10_000)
    }

    private fun oid(content: String) = LongObjectId.fromString(sha256(content))

    private fun objectPath(content: String): String {
        val oid = sha256(content)

        return "${oid.substring(0, 2)}/${oid.substring(2, 4)}/$oid"
    }

    private fun sha256(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
}
