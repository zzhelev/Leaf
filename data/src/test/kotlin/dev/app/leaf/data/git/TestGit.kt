// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

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
    fun run(workingDir: File, vararg args: String): String {
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
            }
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        check(exitCode == 0) { "'${command.joinToString(" ")}' failed with exit code $exitCode: $output" }

        return output
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
