// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.TaskProgress
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RemoteConfig
import java.io.File

/** Up to this many refs are named to `git ls-remote`; more, and it lists every branch, which is one request too. */
private const val MAX_NAMED_REFS = 20

/**
 * The name on [remote]'s server of the remote-tracking branch [trackingRef] (`refs/remotes/origin/x`), as the
 * remote's fetch refspec maps it, or else the usual `refs/heads/x`.
 */
internal fun Repository.serverRefOf(remote: String, trackingRef: String): String =
    RemoteConfig(config, remote).fetchRefSpecs
        .firstOrNull { !it.isNegative && it.source != null && it.matchDestination(trackingRef) }
        ?.expandFromDestination(trackingRef)
        ?.source
        ?: (Constants.R_HEADS + trackingRef.removePrefix("${Constants.R_REMOTES}$remote/"))

/**
 * What [remote]'s server has now at [serverRefs] (`refs/heads/x`): each one it has, with its commit, from
 * `git ls-remote`. A ref it doesn't have is left out. It changes nothing, on the server or here.
 *
 * @param onProgress as for [GitCliRemoteCommand.run], whose default (the processing screen) applies when it's null.
 */
internal suspend fun GitCliRemoteCommand.serverTips(
    directory: File,
    remote: String,
    serverRefs: Collection<String>,
    onProgress: ((TaskProgress?) -> Unit)? = null,
): Either<Map<String, String>, GitError> {
    val wanted = serverRefs.toSet()
    val args = buildList {
        addAll(listOf("ls-remote", "--refs"))
        // The server sends only its branches then, which a server with many pull request refs makes worth it
        if (wanted.all { it.startsWith(Constants.R_HEADS) }) add("--heads")
        add(remote)
        // git matches these at the end of each name, so the exact ones are picked below
        if (wanted.size <= MAX_NAMED_REFS) addAll(wanted)
    }

    val run = if (onProgress != null) {
        run(directory, args, onProgress = onProgress)
    } else {
        run(directory, args)
    }

    val output = when (run) {
        is Either.Err -> return run
        is Either.Ok -> run.value
    }

    if (output.exitCode != 0) {
        return Either.Err(remoteOperationError(output.exitCode, output.stderr))
    }

    return Either.Ok(
        output.stdout.lineSequence()
            .mapNotNull { line ->
                val (id, ref) = line.trimEnd('\r').split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                (ref to id).takeIf { ref in wanted }
            }
            .toMap()
    )
}
