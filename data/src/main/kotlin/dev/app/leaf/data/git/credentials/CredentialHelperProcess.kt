// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import dev.app.leaf.common.printError
import java.io.BufferedWriter
import java.io.IOException

private const val TAG = "CredentialHelperProcess"

/**
 * The command that runs the credential helper [helper], a `credential.helper` value, for [operation] (`get`, `store`
 * or `erase`) on macOS and Linux. Git builds it the same way:
 * - `!command` is a shell command, such as `!gh auth git-credential` or `!f() { ...; }; f`;
 * - an absolute path runs that program, with any arguments that follow it;
 * - anything else names a helper that runs as `git credential-<name>`, such as `osxkeychain` or `manager`. Git finds
 *   `git-credential-<name>` in its own folder or on the PATH.
 *
 * It runs through `/bin/sh`, as git does, so a command given by name is looked up on the PATH that the process gets.
 * Java's [ProcessBuilder] would look it up on the PATH that Leaf was started with.
 */
internal fun posixCredentialHelperCommand(helper: String, operation: String): List<String> {
    val command = when {
        helper.startsWith("!") -> helper.substring(1)
        helper.startsWith("/") -> helper
        else -> "git credential-$helper"
    }

    return listOf("/bin/sh", "-c", "$command $operation")
}

/**
 * [use] for the input of a credential helper. Like git, it ignores a helper that exits without reading its input,
 * for example because its command wasn't found. A helper that gives no credentials leads to asking the user.
 */
internal fun BufferedWriter.useForHelperInput(block: (BufferedWriter) -> Unit) {
    try {
        use(block)
    } catch (e: IOException) {
        printError(TAG, "The credential helper did not read its input: ${e.message}")
    }
}
