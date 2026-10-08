// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.cli.GitCliOutput
import dev.app.leaf.domain.errors.RejectReason
import dev.app.leaf.domain.errors.RejectedRef
import dev.app.leaf.domain.errors.RemoteOperationError

/**
 * A ref in `git push --porcelain`'s output: `<flag>\t<source>:<destination>\t<summary> (<reason>)`, where the flag is
 * ` ` (fast-forward), `+` (forced), `-` (deleted), `*` (new), `=` (up to date) or `!` (rejected).
 */
data class PushedRef(
    val flag: Char,
    val source: String,
    val destination: String,
    val summary: String,
    val reason: String?,
) {
    val isRejected get() = flag == '!'
}

private val PORCELAIN_REF = Regex("""^([ +\-*=!])\t([^\t]*):([^\t:]*)\t(.*)$""")
private val SUMMARY_REASON = Regex("""^(.*?)(?: \((.*)\))?$""")

/** The refs in `git push --porcelain`'s stdout. Its other lines (`To <url>`, `Done`, upstream messages) are skipped. */
fun parsePushPorcelain(stdout: String): List<PushedRef> {
    return stdout.lineSequence()
        .mapNotNull { PORCELAIN_REF.matchEntire(it) }
        .map { match ->
            val (flag, source, destination, rest) = match.destructured
            val summary = SUMMARY_REASON.matchEntire(rest)

            PushedRef(
                flag = flag.single(),
                source = source,
                destination = destination,
                summary = summary?.groupValues?.get(1) ?: rest,
                reason = summary?.groupValues?.get(2)?.ifEmpty { null },
            )
        }
        .toList()
}

/** Whether `git push --porcelain` reached the remote, which then took the credentials: it lists the refs then. */
fun pushReachedRemote(output: GitCliOutput) = output.exitCode == 0 || parsePushPorcelain(output.stdout).isNotEmpty()

/** Why `git push --porcelain` failed: the refs that the remote refused, or else what git's stderr says. */
fun pushFailure(output: GitCliOutput): RemoteOperationError {
    val rejected = parsePushPorcelain(output.stdout).filter { it.isRejected }

    if (rejected.isEmpty()) {
        return remoteOperationError(output.exitCode, output.stderr)
    }

    return RemoteOperationError.RefsRejected(rejected.map { it.toRejectedRef() }, readableGitOutput(output.stderr))
}

fun PushedRef.toRejectedRef(): RejectedRef {
    val reason = when {
        summary == "[remote rejected]" -> RejectReason.REMOTE_REJECTED
        reason == "fetch first" -> RejectReason.FETCH_FIRST
        reason == "non-fast-forward" -> RejectReason.NON_FAST_FORWARD
        reason == "stale info" -> RejectReason.STALE_INFO
        else -> RejectReason.OTHER
    }

    return RejectedRef(destination, reason, detail = this.reason ?: summary)
}
