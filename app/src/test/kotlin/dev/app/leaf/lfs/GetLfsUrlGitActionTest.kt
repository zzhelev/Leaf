// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.lfs

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.models.OperationType.DOWNLOAD
import dev.app.leaf.domain.models.OperationType.UPLOAD
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import javax.inject.Provider

/** Where Leaf's built-in LFS client finds the LFS server of a repository's remote. */
class GetLfsUrlGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private var repositories = 0
    private val configDir by lazy { File(tempDir, "config") }

    /** The user's config, for JGit and for the git that git-lfs runs. */
    private val userConfigFile by lazy { File(configDir, "user.gitconfig") }

    @BeforeEach
    fun isolateJGitConfig() {
        // Keeps the developer's git config, and its url.<base>.insteadOf, out of the test
        SystemReader.setInstance(object : SystemReader.Delegate(originalReader) {
            override fun openUserConfig(parent: Config?, fs: FS) = FileBasedConfig(parent, userConfigFile, fs)

            override fun openSystemConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

            override fun openJGitConfig(parent: Config?, fs: FS) =
                FileBasedConfig(parent, File(configDir, "jgit.config"), fs)
        })
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the LFS server of an SSH remote is the remote, which git-lfs asks with git-lfs-authenticate`(): Unit =
        runBlocking {
            assertEquals(
                LfsServer("ssh://git@example.com:2222/org/repo.git", "ssh://git@example.com:2222/org/repo.git"),
                lfsServerOfOnlyRemote("ssh://git@example.com:2222/org/repo.git"),
            )
            assertEquals(
                LfsServer("git@example.com:org/repo", "git@example.com:org/repo"),
                lfsServerOfOnlyRemote("git@example.com:org/repo"),
            )
        }

    @Test
    fun `the LFS server of an HTTPS remote is under its info-lfs`(): Unit = runBlocking {
        assertEquals(
            LfsServer("https://example.com/org/repo.git/info/lfs", "https://example.com/org/repo"),
            lfsServerOfOnlyRemote("https://example.com/org/repo"),
        )
    }

    @Test
    fun `an lfs url in the repository's config is the LFS server, and the remote's URL is asked for credentials`():
        Unit = runBlocking {
        val server = lfsServerOfOnlyRemote("https://example.com/org/repo") { git ->
            git.repository.config.apply { setString("lfs", null, "url", "https://lfs.example.com/org/repo") }.save()
        }

        assertEquals(LfsServer("https://lfs.example.com/org/repo", "https://example.com/org/repo"), server)
    }

    @Test
    fun `a remote's lfsurl is its LFS server, unless lfs url is set`(): Unit = runBlocking {
        val remoteLfsUrl: (Git) -> Unit = { git ->
            git.repository.config.apply { setString("remote", "origin", "lfsurl", "https://lfs.example.com/origin") }
                .save()
        }
        val expected = LfsServer("https://lfs.example.com/origin", "https://example.com/org/repo")

        // The only remote, and the remote asked for
        assertEquals(expected, lfsServerOfOnlyRemote("https://example.com/org/repo", configure = remoteLfsUrl))
        assertEquals(
            expected,
            lfsServerOfOnlyRemote("https://example.com/org/repo", "refs/remotes/origin", configure = remoteLfsUrl),
        )

        val withLfsUrl: (Git) -> Unit = { git ->
            remoteLfsUrl(git)
            git.repository.config.apply { setString("lfs", null, "url", "https://lfs.example.com/repo") }.save()
        }
        val lfsUrlServer = LfsServer("https://lfs.example.com/repo", "https://example.com/org/repo")
        assertEquals(lfsUrlServer, lfsServerOfOnlyRemote("https://example.com/org/repo", configure = withLfsUrl))
        assertEquals(
            lfsUrlServer,
            lfsServerOfOnlyRemote("https://example.com/org/repo", "refs/remotes/origin", configure = withLfsUrl),
        )
    }

    @Test
    fun `the lfsurl of the remote that the branch tracks`(): Unit = runBlocking {
        val server = lfsServerOfOnlyRemote("https://example.com/org/repo") { git ->
            git.remoteAdd().setName("upstream").setUri(URIish("https://example.com/upstream/repo")).call()
            git.repository.config.apply {
                setString("remote", "origin", "lfsurl", "https://lfs.example.com/origin")
                setString("remote", "upstream", "lfsurl", "https://lfs.example.com/upstream")
                setString("branch", "main", "remote", "upstream")
                setString("branch", "main", "merge", "refs/heads/main")
            }.save()
        }

        assertEquals(LfsServer("https://lfs.example.com/upstream", "https://example.com/upstream/repo"), server)
    }

    @Test
    fun `an HTTPS remote's LFS server is under its dot-git info-lfs, however the remote is found`(): Unit =
        runBlocking {
            for (url in listOf("https://example.com/org/repo", "https://example.com/org/repo/")) {
                val expected = LfsServer("https://example.com/org/repo.git/info/lfs", url)

                // The only remote, the remote asked for, and the remote that the branch tracks
                assertEquals(expected, lfsServerOfOnlyRemote(url))
                assertEquals(expected, lfsServerOfOnlyRemote(url, "refs/remotes/origin"))
                assertEquals(expected, lfsServerOfOnlyRemote(url) { it.config("branch.main.remote" to "origin") })
            }

            val endingInGit = "https://example.com/org/repo.git"
            assertEquals(
                LfsServer("https://example.com/org/repo.git/info/lfs", endingInGit),
                lfsServerOfOnlyRemote(endingInGit, "refs/remotes/origin"),
            )
        }

    @Test
    fun `an upload takes lfs pushurl, lfs url, the remote's lfspushurl, its lfsurl, its push URL, then its URL`():
        Unit = runBlocking {
        val repository = repository("https://example.com/org/repo")
        val url = "https://example.com/org/repo"
        val pushUrl = "https://push.example.com/org/repo"

        suspend fun assertServers(upload: LfsServer, download: LfsServer) {
            assertEquals(upload, lfsServer(repository, "refs/remotes/origin", UPLOAD))
            assertEquals(upload, lfsServer(repository, null, UPLOAD))
            assertEquals(download, lfsServer(repository, "refs/remotes/origin", DOWNLOAD))
        }

        val atUrl = LfsServer("$url.git/info/lfs", url)
        assertServers(upload = atUrl, download = atUrl)

        // Credential helpers are asked about the push URL too
        repository.config("remote.origin.pushurl" to pushUrl)
        assertServers(upload = LfsServer("$pushUrl.git/info/lfs", pushUrl), download = atUrl)

        repository.config("remote.origin.lfsurl" to "https://lfs.example.com/origin")
        assertServers(
            upload = LfsServer("https://lfs.example.com/origin", pushUrl),
            download = LfsServer("https://lfs.example.com/origin", url),
        )

        repository.config("remote.origin.lfspushurl" to "https://lfs.example.com/origin-push")
        assertServers(
            upload = LfsServer("https://lfs.example.com/origin-push", pushUrl),
            download = LfsServer("https://lfs.example.com/origin", url),
        )

        // lfs.url comes before the remote's lfspushurl
        repository.config("lfs.url" to "https://lfs.example.com/repo")
        assertServers(
            upload = LfsServer("https://lfs.example.com/repo", pushUrl),
            download = LfsServer("https://lfs.example.com/repo", url),
        )

        repository.config("lfs.pushurl" to "https://lfs.example.com/repo-push")
        assertServers(
            upload = LfsServer("https://lfs.example.com/repo-push", pushUrl),
            download = LfsServer("https://lfs.example.com/repo", url),
        )
    }

    @Test
    fun `without a remote asked for, the branch's upstream remote, remote lfsdefault, the only remote, then origin`():
        Unit = runBlocking {
        val repository = repository("https://example.com/origin/repo")
        val origin = LfsServer("https://example.com/origin/repo.git/info/lfs", "https://example.com/origin/repo")
        val upstream = LfsServer("https://example.com/upstream/repo.git/info/lfs", "https://example.com/upstream/repo")
        val fork = LfsServer("https://example.com/fork/repo.git/info/lfs", "https://example.com/fork/repo")

        assertEquals(origin, lfsServer(repository))

        Git.open(repository).use { git ->
            git.remoteRemove().setRemoteName("origin").call()
            git.remoteAdd().setName("upstream").setUri(URIish(upstream.remoteUrl)).call()
        }
        // The only remote, although it isn't origin
        assertEquals(upstream, lfsServer(repository))

        // Several, none of them origin
        Git.open(repository).use { git -> git.remoteAdd().setName("fork").setUri(URIish(fork.remoteUrl)).call() }
        assertNull(lfsServer(repository))

        repository.config("remote.lfsdefault" to "fork")
        assertEquals(fork, lfsServer(repository))

        // The upstream remote comes first
        repository.config("branch.main.remote" to "upstream", "branch.main.merge" to "refs/heads/main")
        assertEquals(upstream, lfsServer(repository))

        // A detached HEAD has no upstream, even at the commit of a branch that has one
        Git.open(repository).use { git -> git.checkout().setName(git.repository.resolve("HEAD").name).call() }
        assertEquals(fork, lfsServer(repository))

        // Several, origin among them
        Git.open(repository).use { git -> git.remoteAdd().setName("origin").setUri(URIish(origin.remoteUrl)).call() }
        repository.unsetConfig("remote.lfsdefault")
        assertEquals(origin, lfsServer(repository))
    }

    @Test
    fun `a remote without a server of its own gets origin's, and credential helpers are asked about the server`():
        Unit = runBlocking {
        val repository = repository("https://example.com/org/repo")
        val originsServer = LfsServer("https://example.com/org/repo.git/info/lfs", remoteUrl = null)

        // A branch that tracks a local branch, whose remote is .
        repository.config("branch.main.remote" to ".", "branch.main.merge" to "refs/heads/other")
        assertEquals(originsServer, lfsServer(repository))
        assertEquals(originsServer, lfsServer(repository, "refs/remotes/missing"))
        assertEquals(originsServer, lfsServer(repository, "refs/remotes/missing", UPLOAD))

        // A remote with an LFS server and no URL
        repository.config("remote.lfsonly.lfsurl" to "https://lfs.example.com/lfsonly")
        assertEquals(
            LfsServer("https://lfs.example.com/lfsonly", remoteUrl = null),
            lfsServer(repository, "refs/remotes/lfsonly"),
        )

        // Without origin
        Git.open(repository).use { git -> git.remoteRemove().setRemoteName("origin").call() }
        assertNull(lfsServer(repository, "refs/remotes/missing"))
    }

    @Test
    fun `the server is the one that git-lfs uses, to download and to upload`(): Unit = runBlocking {
        val gitLfsVersion = runCatching { runGit(tempDir, "lfs", "version") }.getOrDefault("")
        assumeTrue(gitLfsVersion.startsWith("git-lfs/")) { "git-lfs isn't installed" }

        // Nothing listens there, so git-lfs names the server in its error, and gives up at once
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        fun url(path: String) = "http://127.0.0.1:$port/$path"

        val repository = File(tempDir, "lfs")
        runGit(tempDir, "init", "-q", "-b", "main", repository.path)
        val contents = "photo".toByteArray()
        val oid = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it) }
        File(repository, "photo.bin").writeText("version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize 5\n")
        File(repository, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        runGit(repository, "add", "-A")
        runGit(repository, "-c", "user.name=Leaf Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "LFS")
        val lfsObject = File(repository, ".git/lfs/objects/${oid.take(2)}/${oid.substring(2, 4)}/$oid")
        val steps = mutableListOf<String>()

        /** Runs git with [args], then compares Leaf's servers with the ones git-lfs asks. */
        suspend fun step(description: String, vararg args: String) {
            runGit(repository, *args)
            steps += description

            val remotes = runGit(repository, "remote").lines().filter { it.isNotEmpty() }
            // git lfs env lists what a download from each remote asks
            val env = runGit(repository, "lfs", "env").lines()

            for (remote in remotes) {
                val endpoint = env.firstOrNull { it.startsWith("Endpoint ($remote)=") }
                    ?: env.firstOrNull { it.startsWith("Endpoint=") }.takeIf { remote == "origin" }
                val gitLfsServer = endpoint?.substringAfter("=")?.substringBefore(" (auth=")?.takeIf { it.isNotEmpty() }

                assertEquals(gitLfsServer, lfsServer(repository, Constants.R_REMOTES + remote)?.url) {
                    "Download from $remote after $steps"
                }

                lfsObject.parentFile.mkdirs()
                lfsObject.writeBytes(contents)
                val pushed = runGit(repository, "lfs", "push", remote, "main")

                assertEquals(askedServer(pushed), lfsServer(repository, Constants.R_REMOTES + remote, UPLOAD)?.url) {
                    "Upload to $remote after $steps"
                }
            }

            // Without a remote, git lfs fetch asks the one that git-lfs picks
            lfsObject.delete()
            val fetched = runGit(repository, "lfs", "fetch")

            assertEquals(askedServer(fetched), lfsServer(repository)?.url) { "Download after $steps" }
        }

        step("only upstream", "remote", "add", "upstream", url("upstream/repo"))
        step("fork, ending with .git/", "remote", "add", "fork", url("fork/repo.git/"))
        step("remote.lfsdefault", "config", "remote.lfsdefault", "fork")
        step("origin", "remote", "add", "origin", url("origin/repo"))
        step("remote.lfsdefault naming no remote", "config", "remote.lfsdefault", "missing")
        step("no remote.lfsdefault", "config", "--unset", "remote.lfsdefault")
        step("branch.main.remote", "config", "branch.main.remote", "upstream")
        step("branch.main.merge", "config", "branch.main.merge", "refs/heads/main")
        step("a local upstream", "config", "branch.main.remote", ".")
        step("a push URL", "config", "remote.upstream.pushurl", url("upstream-push/repo"))
        step("remote.upstream.lfsurl", "config", "remote.upstream.lfsurl", url("upstream-lfs"))
        step("remote.upstream.lfspushurl", "config", "remote.upstream.lfspushurl", url("upstream-lfs-push"))
        step("remote.fork.lfsurl in .lfsconfig", "config", "-f", ".lfsconfig", "remote.fork.lfsurl", url("fork-lfs"))
        // git-lfs ignores it there
        step(
            "remote.fork.lfspushurl in .lfsconfig",
            "config", "-f", ".lfsconfig", "remote.fork.lfspushurl", url("fork-lfs-push"),
        )
        step("lfs.url", "config", "lfs.url", url("lfs"))
        // It beats lfs.url in .git/config
        step("lfs.pushurl in .lfsconfig", "config", "-f", ".lfsconfig", "lfs.pushurl", url("lfs-push"))
    }

    /** The server that git-lfs asked, as its output names it, or null when it had none. */
    private fun askedServer(output: String): String? {
        Regex("""Post "(.+?)/objects/batch"""").find(output)?.let { return it.groupValues[1] }

        if ("missing protocol" in output) {
            return null
        }

        return fail("git-lfs asked no server:\n$output")
    }

    /** Runs git as git-lfs needs it, with the test's user config and no system config, and returns what it printed. */
    private fun runGit(directory: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    keys.removeAll { it.startsWith("GIT_") }
                    put("GIT_CONFIG_GLOBAL", userConfigFile.absolutePath)
                    put("GIT_CONFIG_NOSYSTEM", "1")
                    put("GIT_TERMINAL_PROMPT", "0")
                    put("HOME", tempDir.absolutePath)
                    put("LC_ALL", "C")
                }
            }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor()

        return output
    }

    /**
     * The LFS server for [remoteName] of a repository whose only remote is `origin` at [url], and whose branch has no
     * upstream, once [configure] has changed them.
     */
    private suspend fun lfsServerOfOnlyRemote(
        url: String,
        remoteName: String? = null,
        configure: (Git) -> Unit = {},
    ): LfsServer? = lfsServer(repository(url, configure), remoteName)

    /** A repository with one commit on `main`, whose only remote is `origin` at [url], once [configure] has run. */
    private fun repository(url: String, configure: (Git) -> Unit = {}): File {
        // A repository of its own each time, as the settings would carry over
        val directory = File(tempDir, "repo-${repositories++}")

        Git.init().setDirectory(directory).setInitialBranch("main").call().use { git ->
            val identity = PersonIdent("Leaf Test", "test@example.invalid")
            git.commit().setMessage("Initial commit").setAllowEmpty(true).setAuthor(identity).setCommitter(identity).call()
            git.remoteAdd().setName("origin").setUri(URIish(url)).call()
            configure(git)
        }

        return directory
    }

    /**
     * The LFS server for [operation] with [remoteName] in [repository]. git isn't found, so the settings come from
     * JGit's config, which the test keeps apart. `git config` is tested in `GetLfsUrlSettingGitActionTest`.
     */
    private suspend fun lfsServer(
        repository: File,
        remoteName: String? = null,
        operation: OperationType = DOWNLOAD,
    ): LfsServer? = Git.open(repository).use { git -> action(git.repository, remoteName, operation) }

    private val action by lazy {
        // Nothing here runs a hook or a filter, which would need the login shell's environment
        val jgit = JGit(Provider { error("Only used on Windows") }, LoginShellEnvironment(ProcessRunner()))
        val settings = mockk<AppSettingsRepository> {
            every { gitExecutablePath } returns flowOf(File(tempDir, "no-git").path)
        }
        val withoutGit = GitCli(
            GitExecutableLocator(ProcessRunner()),
            ProcessRunner(),
            AppSettingsService(settings),
            LoginShellEnvironment(ProcessRunner()),
        )

        GetLfsUrlGitAction(
            GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
            GetLfsUrlSettingGitAction(withoutGit),
        )
    }

    /** Sets each `section[.subsection].name` key to its value in the repository's config. */
    private fun File.config(vararg settings: Pair<String, String>) = Git.open(this).use { it.config(*settings) }

    private fun Git.config(vararg settings: Pair<String, String>) {
        repository.config.apply {
            for ((key, value) in settings) {
                setString(key.substringBefore('.'), subsectionOf(key), key.substringAfterLast('.'), value)
            }
        }.save()
    }

    private fun File.unsetConfig(key: String) = Git.open(this).use { git ->
        git.repository.config.apply {
            unset(key.substringBefore('.'), subsectionOf(key), key.substringAfterLast('.'))
        }.save()
    }

    private fun subsectionOf(key: String) =
        key.substringAfter('.').substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
}
