// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/** Keys and signatures made by the real ssh-keygen, in [dir]. Tests that use it are skipped without ssh-keygen. */
class TestKeys(private val dir: File) {
    init {
        assumeTrue(hasSshKeygen(), "ssh-keygen isn't installed")
    }

    /** A key without a passphrase. Returns its private key file; the public one is next to it, with `.pub`. */
    fun newKey(name: String, type: String = "ed25519"): File {
        val key = File(dir, name)
        val bits = if (type == "rsa") listOf("-b", "2048") else emptyList()
        sshKeygen(listOf("-q", "-t", type) + bits + listOf("-N", "", "-C", "test-$name", "-f", key.path))

        return key
    }

    fun publicKeyLine(key: File): String = File(key.path + ".pub").readText().trim()

    fun trusted(name: String, key: File): TrustedKey = TrustedKey(name, publicKeyLine(key))

    /** What `ssh-keygen -Y sign` writes for [message]. */
    fun sign(
        key: File,
        message: ByteArray,
        namespace: String = SIGNATURE_NAMESPACE,
        options: List<String> = emptyList(),
    ): ByteArray {
        val file = File.createTempFile("message", ".txt", dir)
        file.writeBytes(message)
        sshKeygen(listOf("-Y", "sign", "-n", namespace, "-f", key.path) + options + listOf(file.path))

        return File(file.path + ".sig").readBytes()
    }

    /** Whether ssh-keygen's own check accepts [signature] of [message] by [key]: an independent second opinion. */
    fun sshKeygenAccepts(key: File, message: ByteArray, signature: ByteArray, namespace: String = SIGNATURE_NAMESPACE): Boolean {
        val allowed = File.createTempFile("allowed", ".txt", dir)
        allowed.writeText("release namespaces=\"$namespace\" ${publicKeyLine(key)}\n")
        val sig = File.createTempFile("signature", ".sig", dir)
        sig.writeBytes(signature)

        val process = ProcessBuilder(
            "ssh-keygen", "-Y", "verify", "-f", allowed.path, "-I", "release", "-n", namespace, "-s", sig.path,
        )
            .redirectErrorStream(true)
            .also { it.environment().remove("SSH_AUTH_SOCK") }
            .start()
        process.outputStream.use { it.write(message) }
        process.inputStream.readAllBytes()

        return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    private fun sshKeygen(arguments: List<String>) {
        val process = ProcessBuilder(listOf("ssh-keygen") + arguments)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .also { it.environment().remove("SSH_AUTH_SOCK") }
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)

        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "ssh-keygen ${arguments.joinToString(" ")} failed: $output"
        }
    }

    companion object {
        fun hasSshKeygen(): Boolean = try {
            ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().apply {
                inputStream.readAllBytes()
                waitFor(10, TimeUnit.SECONDS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
