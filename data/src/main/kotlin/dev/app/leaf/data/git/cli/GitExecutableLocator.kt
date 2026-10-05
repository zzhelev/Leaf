package dev.app.leaf.data.git.cli

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.gitcli.GitExecutable
import dev.app.leaf.domain.gitcli.GitVersion
import dev.app.leaf.domain.gitcli.IGitExecutableLocator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitExecutableLocator"

/** On macOS, a shim that pops up the "install command line developer tools" dialog when they are missing. */
private const val MAC_SYSTEM_GIT = "/usr/bin/git"
private val VERSION_CHECK_TIMEOUT = 10.seconds

/**
 * Finds the git CLI binary. A configured path is used as is (and reported as an error when it doesn't work), otherwise
 * well-known locations are searched before PATH, as apps launched from the macOS Finder or Dock get a minimal PATH.
 */
@Singleton
class GitExecutableLocator @Inject constructor(
    private val processRunner: ProcessRunner,
) : IGitExecutableLocator {
    private val mutex = Mutex()
    private var cached: CachedExecutable? = null

    override suspend fun locate(configuredPath: String?): Either<GitExecutable, GitCliError> = mutex.withLock {
        val path = configuredPath?.trim()?.takeIf { it.isNotEmpty() }

        cached?.let { cached ->
            if (cached.configuredPath == path) {
                return@withLock Either.Ok(cached.executable)
            }
        }

        val result = if (path != null) {
            validateConfiguredPath(path)
        } else {
            findUsableExecutable(gitExecutableCandidates(currentOs, System.getenv("PATH"), System::getenv))
        }

        if (result is Either.Ok) {
            printLog(TAG, "Using git ${result.value.version} at ${result.value.path}")
            cached = CachedExecutable(path, result.value)
        }

        result
    }

    /** Forgets the cached executable, for example after it could not be started anymore. */
    suspend fun invalidate() = mutex.withLock {
        cached = null
    }

    private suspend fun validateConfiguredPath(path: String): Either<GitExecutable, GitCliError> {
        val file = File(path)

        val error = when {
            !file.exists() -> "the file does not exist"
            !file.isFile -> "it is not a file"
            !file.canExecute() -> "the file is not executable"
            else -> null
        }

        if (error != null) {
            return Either.Err(GitCliError.InvalidConfiguredPath(path, error))
        }

        return when (val version = readVersion(path)) {
            is Either.Err -> Either.Err(GitCliError.InvalidConfiguredPath(path, version.error))
            is Either.Ok -> toExecutable(path, version.value)
        }
    }

    /** Returns the first candidate that runs and is recent enough. */
    internal suspend fun findUsableExecutable(candidates: List<String>): Either<GitExecutable, GitCliError> {
        var unsupported: GitCliError.UnsupportedVersion? = null

        for (path in candidates) {
            val file = File(path)

            if (!file.isFile || !file.canExecute()) {
                continue
            }

            if (currentOs == OS.MAC && path == MAC_SYSTEM_GIT && !hasMacCommandLineTools()) {
                printLog(TAG, "Skipping $MAC_SYSTEM_GIT, the command line developer tools are not installed")
                continue
            }

            when (val version = readVersion(path)) {
                is Either.Err -> printError(TAG, "Ignoring $path: ${version.error}")
                is Either.Ok -> when (val executable = toExecutable(path, version.value)) {
                    is Either.Ok -> return executable
                    is Either.Err -> if (unsupported == null) {
                        unsupported = executable.error as? GitCliError.UnsupportedVersion
                    }
                }
            }
        }

        return Either.Err(unsupported ?: GitCliError.GitNotFound(candidates))
    }

    private fun toExecutable(path: String, version: GitVersion): Either<GitExecutable, GitCliError> {
        return if (version >= GitVersion.MINIMUM_SUPPORTED) {
            Either.Ok(GitExecutable(path, version))
        } else {
            Either.Err(
                GitCliError.UnsupportedVersion(path, version.toString(), GitVersion.MINIMUM_SUPPORTED.toString())
            )
        }
    }

    /** Runs `git --version`, returning the parsed version or the reason it failed. */
    private suspend fun readVersion(path: String): Either<GitVersion, String> {
        val outcome = try {
            processRunner.run(listOf(path, "--version"), null, mapOf("LC_ALL" to "C"), VERSION_CHECK_TIMEOUT)
        } catch (e: IOException) {
            return Either.Err("it could not be started: ${e.message}")
        }

        return when (outcome) {
            ProcessOutcome.TimedOut -> Either.Err("'--version' did not finish within $VERSION_CHECK_TIMEOUT")
            is ProcessOutcome.Completed -> {
                val version = GitVersion.parse(outcome.stdout)

                when {
                    outcome.exitCode != 0 -> Either.Err("'--version' failed with exit code ${outcome.exitCode}")
                    version == null -> Either.Err("'--version' printed unexpected output: ${outcome.stdout.trim()}")
                    else -> Either.Ok(version)
                }
            }
        }
    }

    private suspend fun hasMacCommandLineTools(): Boolean {
        return try {
            val outcome = processRunner.run(listOf("/usr/bin/xcode-select", "-p"), null, emptyMap(), VERSION_CHECK_TIMEOUT)
            outcome is ProcessOutcome.Completed && outcome.exitCode == 0 && File(outcome.stdout.trim()).isDirectory
        } catch (e: IOException) {
            false
        }
    }

    private data class CachedExecutable(val configuredPath: String?, val executable: GitExecutable)
}

/**
 * Candidate git binaries in search order: well-known install locations first, then PATH (which may be minimal when
 * launched from a desktop environment), then the platform defaults.
 */
internal fun gitExecutableCandidates(os: OS, pathVariable: String?, getenv: (String) -> String?): List<String> {
    val pathSeparator = if (os == OS.WINDOWS) ";" else ":"
    val fileSeparator = if (os == OS.WINDOWS) "\\" else "/"
    val binaryName = if (os == OS.WINDOWS) "git.exe" else "git"
    val fromPath = pathVariable.orEmpty()
        .split(pathSeparator)
        .filter { it.isNotBlank() }
        .map { it.trimEnd('/', '\\') + fileSeparator + binaryName }

    val candidates = when (os) {
        OS.MAC -> listOf("/opt/homebrew/bin/git", "/usr/local/bin/git", MAC_SYSTEM_GIT) + fromPath
        OS.WINDOWS -> fromPath + listOfNotNull(
            getenv("ProgramFiles")?.let { "$it\\Git\\cmd\\git.exe" },
            getenv("ProgramFiles(x86)")?.let { "$it\\Git\\cmd\\git.exe" },
            getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git\\cmd\\git.exe" },
        )

        OS.LINUX, OS.UNKNOWN -> fromPath + listOf("/usr/bin/git", "/usr/local/bin/git")
    }

    return candidates.distinct()
}
