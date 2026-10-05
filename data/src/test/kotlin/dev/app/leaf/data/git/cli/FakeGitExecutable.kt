package dev.app.leaf.data.git.cli

import java.io.File

/**
 * Writes an executable shell script that answers `--version` with [versionOutput] and runs [body] for any other
 * arguments. Only usable on Unix-like systems.
 */
fun fakeGitExecutable(
    directory: File,
    name: String,
    versionOutput: String = "git version 2.40.1",
    body: String = "exit 0",
): File {
    directory.mkdirs()

    val file = File(directory, name)
    file.writeText(
        """
        |#!/bin/sh
        |if [ "${'$'}1" = "--version" ]; then
        |  echo "$versionOutput"
        |  exit 0
        |fi
        |$body
        |""".trimMargin()
    )
    file.setExecutable(true)

    return file
}
