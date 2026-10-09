// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.findGitForWindows
import java.io.File

/**
 * Where programs such as gpg are installed on macOS (Homebrew, GPG Suite), searched after the PATH in case the login
 * shell failed.
 */
private val MAC_PROGRAM_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/local/MacGPG2/bin")

/**
 * The folders of a Git for Windows install that its git puts ahead of PATH, in this order, so `git commit -S` finds the
 * gpg bundled in `usr\bin` before Gpg4win's. See `setup_environment` in git-wrapper.c (`cmd\git.exe`) and
 * `append_system_bin_dirs` in compat/mingw.c (git.exe started without `MSYSTEM`). The first is the `bin` of git's MSYS2
 * environment, which depends on the build: `ucrt64` since Git for Windows 2.56, `mingw64` before, `clangarm64` on
 * ARM64, `mingw32` for 32-bit MinGit. Both also add `%HOME%\bin`, which Leaf leaves out.
 */
private val GIT_FOR_WINDOWS_PROGRAM_DIRECTORIES =
    listOf("ucrt64/bin", "mingw64/bin", "clangarm64/bin", "mingw32/bin", "usr/bin")

/**
 * Where [program] is, looked up on the PATH of [environment], the variables the program gets, and on Windows first in
 * Git for Windows' folders, as the other [locateProgram] says. gpg, ssh-keygen and ssh are found this way.
 */
internal fun locateProgram(program: String, environment: Map<String, String>): String? {
    val pathVariable = environment["PATH"] ?: System.getenv("PATH")
    val gitForWindows = if (currentOs == OS.WINDOWS) findGitForWindows(pathVariable, System::getenv) else null

    return locateProgram(program, currentOs, pathVariable, gitForWindows?.let(::File))
}

/**
 * The program to start for [program], such as a `gpg.program` value. A path is used as it is. A name is looked up here
 * the way git looks it up, as Java's [ProcessBuilder] would search elsewhere:
 * - On macOS and Linux, on [pathVariable], the PATH that the program gets, as git's `execvp` does, and on macOS then in
 *   [MAC_PROGRAM_DIRECTORIES]. Java would search the PATH that Leaf was started with, which has no Homebrew folders
 *   when Leaf is opened from the Finder.
 * - On Windows, in the folders of [gitForWindows] first, then on [pathVariable], as Git for Windows does: see
 *   [GIT_FOR_WINDOWS_PROGRAM_DIRECTORIES], and `path_lookup` in compat/mingw.c, which tries `<name>.exe`, then the name
 *   as it is, in each folder. Windows would search Leaf's own folder and the system folders before PATH, and never
 *   Git's, so it would miss the gpg bundled with Git.
 *
 * Null when the name is found nowhere.
 */
internal fun locateProgram(program: String, os: OS, pathVariable: String?, gitForWindows: File? = null): String? {
    return when {
        program.contains('/') || (os == OS.WINDOWS && program.contains('\\')) -> program
        os == OS.WINDOWS -> locateWindowsProgram(program, pathVariable, gitForWindows)
        else -> locatePosixProgram(program, os, pathVariable)
    }
}

private fun locatePosixProgram(program: String, os: OS, pathVariable: String?): String? {
    val directories = pathVariable.orEmpty().split(':').filter { it.isNotBlank() } +
            if (os == OS.MAC) MAC_PROGRAM_DIRECTORIES else emptyList()

    return directories
        .map { File(it, program) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.path
}

private fun locateWindowsProgram(program: String, pathVariable: String?, gitForWindows: File?): String? {
    val gitDirectories = gitForWindows
        ?.let { install -> GIT_FOR_WINDOWS_PROGRAM_DIRECTORIES.map { File(install, it) } }
        .orEmpty()
    val pathDirectories = pathVariable.orEmpty().split(';').filter { it.isNotEmpty() }.map(::File)
    val names = if (program.endsWith(".exe", ignoreCase = true)) listOf(program) else listOf("$program.exe", program)

    return (gitDirectories + pathDirectories)
        .flatMap { directory -> names.map { File(directory, it) } }
        .firstOrNull { it.isFile }
        ?.path
}
