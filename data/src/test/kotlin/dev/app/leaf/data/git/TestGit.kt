// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import javax.inject.Provider

/** A [JGit] for tests on macOS and Linux, where [shellVariables] stand for what the login shell adds to hooks. */
fun testJGit(shellVariables: Map<String, String> = emptyMap()) =
    JGit(Provider { error("Only used on Windows") }, LoginShellEnvironment { shellVariables })

/**
 * A [GitCli] for tests, where [shellVariables] stand for what the login shell adds, and [configuredPath] for the git
 * executable set in Leaf's settings.
 */
fun testGitCli(shellVariables: Map<String, String> = emptyMap(), configuredPath: String? = null): GitCli {
    val appSettingsRepository = mockk<AppSettingsRepository> {
        every { gitExecutablePath } returns flowOf(configuredPath)
    }

    return GitCli(
        GitExecutableLocator(ProcessRunner()),
        ProcessRunner(),
        AppSettingsService(appSettingsRepository),
        LoginShellEnvironment { shellVariables },
    )
}

/**
 * Points JGit's user, system and JGit config files to [configDir], keeping the developer's own config out of tests.
 * Install it with [SystemReader.setInstance] and restore the previous instance after the test.
 */
class IsolatedSystemReader(
    private val configDir: File,
    delegate: SystemReader,
) : SystemReader.Delegate(delegate) {
    val userConfigFile get() = File(configDir, "user.gitconfig")
    val jGitConfigFile get() = File(configDir, "jgit.config")

    override fun openUserConfig(parent: Config?, fs: FS) = FileBasedConfig(parent, userConfigFile, fs)

    override fun openSystemConfig(parent: Config?, fs: FS) =
        FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

    override fun openJGitConfig(parent: Config?, fs: FS) = FileBasedConfig(parent, jGitConfigFile, fs)
}

/**
 * Runs the git CLI to set up test repositories (JGit can't create linked worktrees), ignoring the developer's global
 * and system git config.
 */
class TestGitCli(private val emptyGlobalConfig: File) {
    fun run(workingDir: File, vararg args: String): String = run(workingDir, emptyMap(), *args)

    /** Runs git with extra environment variables, for example `GIT_COMMITTER_DATE` to fix commit and reflog dates. */
    fun run(workingDir: File, env: Map<String, String>, vararg args: String): String {
        val (exitCode, output) = execute(workingDir, env, args)

        check(exitCode == 0) { "'git ${args.joinToString(" ")}' failed with exit code $exitCode: $output" }

        return output
    }

    /**
     * Runs git expecting it to fail, for example to see how git itself refuses something, and returns what it printed.
     * Its messages are in English.
     */
    fun runFailing(workingDir: File, vararg args: String): String {
        val (exitCode, output) = execute(workingDir, mapOf("LC_ALL" to "C"), args)

        check(exitCode != 0) { "'git ${args.joinToString(" ")}' succeeded: $output" }

        return output
    }

    private fun execute(workingDir: File, env: Map<String, String>, args: Array<out String>): Pair<Int, String> {
        emptyGlobalConfig.parentFile.mkdirs()
        emptyGlobalConfig.createNewFile()

        val command = listOf(
            "git",
            "-c", "user.name=Leaf Test",
            "-c", "user.email=test@example.invalid",
            "-c", "init.defaultBranch=main",
            "-c", "commit.gpgSign=false",
            "-c", "protocol.file.allow=always", // Required to add local submodules
            *args,
        )

        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = emptyGlobalConfig.absolutePath
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment().putAll(env)
            }
            .start()

        val output = process.inputStream.bufferedReader().readText()

        return process.waitFor() to output
    }

    /** Creates a repository in [directory] with a single commit on main. */
    fun initRepository(directory: File): File {
        directory.mkdirs()
        run(directory, "init")
        File(directory, "README.md").writeText("Test repository\n")
        run(directory, "add", ".")
        run(directory, "commit", "-m", "Initial commit")

        return directory
    }
}

/** Writes [content] to this file and makes it executable, for hooks, fake shells and fake tools. */
fun File.writeExecutable(content: String): File {
    parentFile.mkdirs()
    writeText(content)
    check(setExecutable(true)) { "Could not make $this executable" }

    return this
}

/**
 * Creates the command `leaf-hook-tool` in [directory], which is on no PATH unless a test puts it there, like a tool
 * installed with Homebrew or nvm. Each run creates the file `ran` next to it. Returns [directory].
 */
fun createHookTool(directory: File): File {
    File(directory, "leaf-hook-tool").writeExecutable("#!/bin/sh\ntouch \"\$(dirname \"\$0\")/ran\"\n")

    return directory
}

/** A hook that runs `leaf-hook-tool` and fails, as `sh` does, when it isn't found. */
const val HOOK_RUNNING_TOOL = "#!/bin/sh\nleaf-hook-tool\n"
