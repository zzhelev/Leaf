// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.SeveralPushUrlsError
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
 * The remote-tracking branch that [remote]'s fetch refspec maps the server's [serverRef] (`refs/heads/x`) to, which a
 * push to it sets (`refs/remotes/origin/x`), or null when it maps none (fork-only).
 */
internal fun Repository.trackingRefOf(remote: String, serverRef: String): String? =
    RemoteConfig(config, remote).fetchRefSpecs
        .firstOrNull { !it.isNegative && it.destination != null && it.matchSource(serverRef) }
        ?.expandFromSource(serverRef)
        ?.destination

/**
 * What [remote]'s server has now at [serverRefs] (`refs/heads/x`): each one it has, with its commit, from
 * `git ls-remote`. A ref it doesn't have is left out. It changes nothing, on the server or here.
 *
 * The server asked is the one that [remote] pushes to, as these questions follow a push (fork-only): `git ls-remote`
 * asks a remote's fetch URL, so a remote whose `pushurl` points elsewhere, such as a fork, gets its push URL asked. A
 * remote that pushes to several URLs gets [SeveralPushUrlsError], as one of them can't say what the others have.
 *
 * @param onProgress as for [GitCliRemoteCommand.run], whose default (the processing screen) applies when it's null.
 */
internal suspend fun GitCliRemoteCommand.serverTips(
    directory: File,
    remote: String,
    serverRefs: Collection<String>,
    onProgress: ((TaskProgress?) -> Unit)? = null,
): Either<Map<String, String>, GitError> {
    val pushUrls = remoteUrls(directory, remote, push = true)
    // The remote's name when it pushes where it fetches from, so that its own settings (`remote.<name>.proxy`) apply
    val server = when {
        pushUrls == null -> remote
        pushUrls.size > 1 -> return Either.Err(SeveralPushUrlsError(remote, pushUrls))
        pushUrls.single() == remoteUrls(directory, remote, push = false)?.firstOrNull() -> remote
        else -> pushUrls.single()
    }

    val wanted = serverRefs.toSet()
    val args = buildList {
        addAll(listOf("ls-remote", "--refs"))
        // The server sends only its branches then, which a server with many pull request refs makes worth it
        if (wanted.all { it.startsWith(Constants.R_HEADS) }) add("--heads")
        add(server)
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
