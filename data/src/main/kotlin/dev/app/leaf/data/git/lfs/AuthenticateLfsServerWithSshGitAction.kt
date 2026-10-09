package dev.app.leaf.data.git.lfs

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.GitBash
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.ProcessOutcome
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IAuthenticateLfsServerWithSshGitAction
import dev.app.leaf.domain.lfs.LfsSshAuthenticateResult
import dev.app.leaf.domain.models.OperationType
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Leaves time for Leaf's dialogs (an unknown host key, a passphrase). Nothing can cancel the checkout whose filter
 * this runs in, so an ssh that hangs must not block it forever.
 */
private val AUTHENTICATE_TIMEOUT = 2.minutes

private val CONFIG_TIMEOUT = 10.seconds

/** What git and git-lfs run when neither `GIT_SSH_COMMAND`, `core.sshCommand` nor `GIT_SSH` says otherwise. */
private const val DEFAULT_SSH = "ssh"

/** git's shell (`SHELL_PATH`), which runs `GIT_SSH_COMMAND` and `core.sshCommand`. */
private const val POSIX_SHELL = "/bin/sh"

/**
 * Asks an SSH remote where its LFS server is, and for the headers to reach it with, for Leaf's built-in LFS client,
 * which git-lfs replaces when it's installed. It runs what git-lfs runs, with the system's ssh:
 * `ssh [-p <port>] [<user>@]<host> 'git-lfs-authenticate <path> <operation>'`, whose answer is JSON.
 *
 * - The ssh program is the one git-lfs picks ([sshProgram]), so `core.sshCommand` applies, read with `git config` so
 *   that `includeIf` does too.
 * - ssh asks through the askpass helper, with Leaf's dialogs, and passphrases are kept per key file, as for the git
 *   commands of `GitCliRemoteCommand`.
 * - It fails with ssh's own messages, which carry the server's.
 *
 * It replaced a libssh session, which didn't follow the user's ssh config, agent or known_hosts.
 */
class AuthenticateLfsServerWithSshGitAction @Inject constructor(
    private val askpassProcessRunner: AskpassProcessRunner,
    private val gitCli: GitCli,
    private val loginShellEnvironment: LoginShellEnvironment,
) : IAuthenticateLfsServerWithSshGitAction {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend operator fun invoke(
        repository: Repository,
        lfsServerUrl: String,
        operationType: OperationType,
    ): LfsSshAuthenticateResult {
        val operation = when (operationType) {
            OperationType.UPLOAD -> "upload"
            OperationType.DOWNLOAD -> "download"
        }

        val environment = loginShellEnvironment.variables()
        val workingDirectory = if (repository.isBare) repository.directory else repository.workTree
        val program = sshProgram(
            variable = { environment[it] ?: System.getenv(it) },
            coreSshCommand = coreSshCommand(repository, workingDirectory),
        )
        val arguments = lfsAuthenticateArguments(URIish(lfsServerUrl), operation)
        val command = sshCommand(program, arguments, environment)

        val answers = askpassProcessRunner.answers()
        val outcome = try {
            askpassProcessRunner.run(command, workingDirectory, environment, AUTHENTICATE_TIMEOUT, answers)
        } catch (e: IOException) {
            throw LfsException("Could not start ${command.first()} to ask $lfsServerUrl for its LFS server: $e")
        }

        val completed = when (outcome) {
            ProcessOutcome.TimedOut -> throw LfsException(
                "ssh didn't finish within ${AUTHENTICATE_TIMEOUT.inWholeSeconds} seconds asking $lfsServerUrl " +
                    "for its LFS server"
            )

            is ProcessOutcome.Completed -> outcome
        }

        // ssh writes warnings to stderr when it succeeds too, such as "Permanently added ... to the list of known
        // hosts", so only its exit code tells
        if (completed.exitCode != 0) {
            val messages = completed.stderr.trim().ifEmpty { "(no output, exit code ${completed.exitCode})" }
            throw LfsException("git-lfs-authenticate failed for $lfsServerUrl: $messages")
        }

        answers.commit()

        return try {
            json.decodeFromString<LfsSshAuthenticateResult>(completed.stdout)
        } catch (e: IllegalArgumentException) { // Also kotlinx.serialization's SerializationException
            throw LfsException("git-lfs-authenticate answered for $lfsServerUrl with what Leaf can't read: ${completed.stdout}")
        }
    }

    /**
     * `core.sshCommand`, read with git, which follows `includeIf`: a different key per folder is a common setup for
     * two accounts on one host. Otherwise from JGit's config, which has no more than git's: when git can't run, or
     * didn't find it (exit code 1).
     */
    private suspend fun coreSshCommand(repository: Repository, workingDirectory: File): String? {
        val args = listOf("--git-dir=${repository.directory.absolutePath}", "config", "--get", "core.sshCommand")
        val output = gitCli.execute(workingDirectory, args, CONFIG_TIMEOUT).okOrNull()

        return if (output?.exitCode == 0) {
            output.stdout.trimEnd('\n')
        } else {
            repository.config.getString("core", null, "sshCommand")
        }
    }
}

/** How to start ssh, as git and git-lfs decide it. */
internal sealed interface SshProgram {
    /** `GIT_SSH_COMMAND` or `core.sshCommand`: a command line, which the shell runs with the arguments after it. */
    data class ShellCommand(val commandLine: String) : SshProgram

    /** `GIT_SSH`, or `ssh`: a program, run without a shell. */
    data class Program(val program: String) : SshProgram
}

/**
 * The ssh to run, in git-lfs's order, which is git's (`get_ssh_command` in connect.c): `GIT_SSH_COMMAND`, then
 * `core.sshCommand`, then `GIT_SSH`, then `ssh`. [variable] reads the environment that ssh gets.
 */
internal fun sshProgram(variable: (String) -> String?, coreSshCommand: String?): SshProgram {
    return variable("GIT_SSH_COMMAND")?.takeIf { it.isNotBlank() }?.let { SshProgram.ShellCommand(it) }
        ?: coreSshCommand?.takeIf { it.isNotBlank() }?.let { SshProgram.ShellCommand(it) }
        ?: variable("GIT_SSH")?.takeIf { it.isNotBlank() }?.let { SshProgram.Program(it) }
        ?: SshProgram.Program(DEFAULT_SSH)
}

/**
 * The arguments that git-lfs gives ssh for `git-lfs-authenticate` (captured from git-lfs 3.8): the port with OpenSSH's
 * `-p` when the URL has one, `[<user>@]<host>`, and the remote command as one argument, with the URL's path as it is:
 * `org/repo.git` for `git@host:org/repo.git`, `/org/repo.git` for `ssh://git@host/org/repo.git`. PuTTY's plink takes
 * `-P` for the port, so it only works on the default port.
 *
 * A host or user that starts with `-` is refused, as ssh would read it as an option.
 */
internal fun lfsAuthenticateArguments(uri: URIish, operation: String): List<String> {
    val host = uri.host?.takeIf { it.isNotEmpty() } ?: throw LfsException("The LFS server's SSH URL $uri has no host")
    val userAndHost = uri.user?.let { "$it@$host" } ?: host

    if (userAndHost.startsWith("-")) {
        throw LfsException("Refusing the SSH host $userAndHost, which ssh would take as an option")
    }

    // JGit leaves out the slash of ssh://host/~/repo, which git-lfs keeps; a URL without a scheme has none
    val path = if (uri.scheme != null && !uri.path.startsWith("/")) "/${uri.path}" else uri.path

    return buildList {
        if (uri.port > 0) {
            add("-p")
            add(uri.port.toString())
        }

        add(userAndHost)
        add("git-lfs-authenticate $path $operation")
    }
}

/**
 * The command to start: a [SshProgram.ShellCommand] runs with git's shell, as git runs it
 * (`sh -c '<command> "$@"' '<command>' <arguments>`), Git Bash's on Windows. A [SshProgram.Program] is looked up like
 * gpg ([locateProgram]): on Windows in Git for Windows first, whose ssh git uses.
 */
internal fun sshCommand(program: SshProgram, arguments: List<String>, environment: Map<String, String>): List<String> {
    return when (program) {
        is SshProgram.ShellCommand -> if (currentOs == OS.WINDOWS) {
            val pathVariable = environment["PATH"] ?: System.getenv("PATH")
            val gitBash = GitBash.find(pathVariable, System::getenv)
                ?: throw LfsException("core.sshCommand needs Git for Windows' shell, which wasn't found")

            gitBash.shellCommand(program.commandLine, arguments)
        } else {
            listOf(POSIX_SHELL, "-c", "${program.commandLine} \"\$@\"", program.commandLine) + arguments
        }

        is SshProgram.Program -> {
            val executable = locateProgram(program.program, environment)
                ?: throw LfsException("${program.program} was not found, which Leaf needs to reach the LFS server")

            listOf(executable) + arguments
        }
    }
}
