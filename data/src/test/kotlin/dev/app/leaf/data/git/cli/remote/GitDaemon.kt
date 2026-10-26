// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a `git daemon` that serves the repositories in [baseDir] and takes pushes, for [block] with its URL for them
 * (`git://127.0.0.1:<port>`), with git kept away from the developer's config ([globalConfig]). Skips the test when it
 * doesn't start.
 *
 * Its server runs apart from the git that pushes, as an ssh or https server does: killing that git doesn't stop what it
 * had handed the server. A remote on disk does stop, as its `git receive-pack` is one of git's own children.
 */
suspend fun withGitDaemon(baseDir: File, globalConfig: File, log: File, block: suspend (baseUrl: String) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val daemon = ProcessBuilder(
        "git", "daemon", "--base-path=${baseDir.absolutePath}", "--export-all", "--enable=receive-pack",
        "--port=$port", "--listen=127.0.0.1", "--reuseaddr",
    )
        .redirectErrorStream(true)
        .redirectOutput(log)
        .apply {
            environment()["GIT_CONFIG_GLOBAL"] = globalConfig.absolutePath
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }
        .start()

    try {
        val listening = withTimeout(10.seconds) {
            while (daemon.isAlive) {
                val accepts = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }
                if (accepts.isSuccess) return@withTimeout true
                delay(50)
            }
            false
        }
        assumeTrue(listening) { "git daemon didn't start: ${log.readText()}" }

        block("git://127.0.0.1:$port")
    } finally {
        daemon.descendants().forEach { it.destroyForcibly() }
        daemon.destroyForcibly()
        daemon.waitFor()
    }
}
