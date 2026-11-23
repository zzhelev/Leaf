// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.editor

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.cli.locateProgram
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.editor.IEditorLauncher
import dev.app.leaf.domain.errors.EditorError
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** How long a started program has to end with an error before it counts as started (fork-only). */
private const val START_CHECK_MILLIS = 3_000L

/** At most this much of a program's output goes into the error. */
private const val MAX_OUTPUT = 4_000

/**
 * Starts editors and the file manager (fork-only), with the login shell's variables, so that a command of the user's
 * own finds what their PATH has when Leaf was opened from the Finder.
 */
class EditorLauncher @Inject constructor(
    private val loginShellEnvironment: LoginShellEnvironment,
) : IEditorLauncher {
    override suspend fun start(command: List<String>, workingDirectory: String): Either<Unit, EditorError> =
        withContext(Dispatchers.IO) {
            val environment = loginShellEnvironment.variables()
            val pathEnvironment = System.getenv() + environment
            val program = locateProgram(command.first(), pathEnvironment) ?: command.first()

            startAndCheck(listOf(program) + command.drop(1), File(workingDirectory), environment, START_CHECK_MILLIS)
        }

    override suspend fun reveal(path: String): Either<Unit, EditorError> = withContext(Dispatchers.IO) {
        val file = File(path)

        when (currentOs) {
            OS.MAC -> startAndCheck(listOf("open", "-R", path), file.parentFile ?: file, emptyMap(), START_CHECK_MILLIS)
            // explorer exits with 1 when it selected the file too, so only a failure to start counts
            OS.WINDOWS -> startAndCheck(
                listOf("explorer", "/select,$path"),
                file.parentFile ?: file,
                emptyMap(),
                checkMillis = 0,
            )

            else -> revealOnLinux(file)
        }
    }

    /**
     * Asks the file manager to select the file through the freedesktop D-Bus interface that Nautilus, Dolphin, Nemo
     * and Thunar have, or else opens its folder with `xdg-open`.
     */
    private fun revealOnLinux(file: File): Either<Unit, EditorError> {
        val uri = URI("file", null, file.absolutePath, null).toASCIIString()
        val dbus = listOf(
            "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
            "--type=method_call", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
            "array:string:$uri", "string:",
        )
        val folder = if (file.isDirectory) file else file.parentFile ?: file

        return when (startAndCheck(dbus, folder, emptyMap(), START_CHECK_MILLIS)) {
            is Either.Ok -> Either.Ok(Unit)
            is Either.Err -> startAndCheck(listOf("xdg-open", folder.path), folder, emptyMap(), START_CHECK_MILLIS)
        }
    }
}

/**
 * Starts [command] in [directory] with [environment] added, and waits up to [checkMillis] for it: it counts as started
 * unless it can't start, or ends in that time with an exit code other than 0, as a command-line tool does that refuses
 * its arguments. One that runs longer is left running: an editor started by its own program. Its output goes to a
 * temporary file, as a pipe that nobody reads could block it.
 */
internal fun startAndCheck(
    command: List<String>,
    directory: File,
    environment: Map<String, String>,
    checkMillis: Long,
): Either<Unit, EditorError> {
    val commandText = command.joinToString(" ")
    val log = try {
        File.createTempFile("leaf-editor-", ".log")
    } catch (e: IOException) {
        null
    }

    val process = try {
        ProcessBuilder(command)
            .directory(directory.takeIf { it.isDirectory })
            .redirectErrorStream(true)
            .apply {
                if (log != null) redirectOutput(log) else redirectOutput(ProcessBuilder.Redirect.DISCARD)
                environment().putAll(environment)
            }
            .start()
    } catch (e: IOException) {
        log?.delete()
        return Either.Err(EditorError.Failed(commandText, exitCode = null, output = e.message.orEmpty()))
    }

    process.outputStream.close()

    val ended = checkMillis > 0 && process.waitFor(checkMillis, TimeUnit.MILLISECONDS)
    val result = if (ended && process.exitValue() != 0) {
        val output = try {
            log?.readText().orEmpty().trim().take(MAX_OUTPUT)
        } catch (e: IOException) {
            ""
        }
        Either.Err(EditorError.Failed(commandText, process.exitValue(), output))
    } else {
        Either.Ok(Unit)
    }

    // A program that still runs keeps writing to it: it goes when Leaf quits where it can't go now (Windows)
    if (log != null && !log.delete()) log.deleteOnExit()

    return result
}
