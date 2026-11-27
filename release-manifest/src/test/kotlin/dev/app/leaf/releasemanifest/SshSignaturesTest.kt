// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SshSignaturesTest {
    @TempDir
    lateinit var dir: File

    private val message = "{\n    \"format\": 1\n}\n".toByteArray()

    @Test
    fun `a signature that ssh-keygen made checks out, and ssh-keygen agrees`() {
        val keys = TestKeys(dir)
        val key = keys.newKey("mac")
        val signature = keys.sign(key, message)

        assertEquals(SignatureVerdict.Valid, verifySshSignature(message, signature, SIGNATURE_NAMESPACE, publicKey(keys, key)))
        assertTrue(keys.sshKeygenAccepts(key, message, signature))
    }

    @Test
    fun `a changed file doesn't`() {
        val keys = TestKeys(dir)
        val key = keys.newKey("mac")
        val signature = keys.sign(key, message)
        val changed = message.copyOf().also { it[it.size - 3] = '2'.code.toByte() }

        assertInvalid(verifySshSignature(changed, signature, SIGNATURE_NAMESPACE, publicKey(keys, key)), "match")
    }

    @Test
    fun `a signature made for another namespace doesn't`() {
        val keys = TestKeys(dir)
        val key = keys.newKey("mac")
        val signature = keys.sign(key, message, namespace = "git")

        assertInvalid(verifySshSignature(message, signature, SIGNATURE_NAMESPACE, publicKey(keys, key)), "git")
    }

    @Test
    fun `a signature made with another key doesn't`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val other = keys.newKey("other")
        val signature = keys.sign(other, message)

        assertInvalid(verifySshSignature(message, signature, SIGNATURE_NAMESPACE, publicKey(keys, mac)), "another key")
    }

    @Test
    fun `a signature over a SHA-256 hash doesn't`() {
        val keys = TestKeys(dir)
        val key = keys.newKey("mac")
        val signature = keys.sign(key, message, options = listOf("-O", "hashalg=sha256"))

        assertInvalid(verifySshSignature(message, signature, SIGNATURE_NAMESPACE, publicKey(keys, key)), "sha256")
    }

    @Test
    fun `a signature made with an RSA key doesn't`() {
        val keys = TestKeys(dir)
        val rsa = keys.newKey("rsa", type = "rsa")
        val ed25519 = keys.newKey("mac")
        val signature = keys.sign(rsa, message)

        assertInvalid(verifySshSignature(message, signature, SIGNATURE_NAMESPACE, publicKey(keys, ed25519)), "Ed25519")
    }

    @Test
    fun `something that isn't a signature doesn't, and nothing is thrown`() {
        val keys = TestKeys(dir)
        val key = publicKey(keys, keys.newKey("mac"))
        val real = keys.sign(keys.newKey("signer"), message).toString(Charsets.UTF_8)
        val lines = real.lines()

        val broken = listOf(
            "",
            "hello",
            "-----BEGIN SSH SIGNATURE-----\n!!!\n-----END SSH SIGNATURE-----\n",
            "-----BEGIN SSH SIGNATURE-----\nU1NIU0lH\n-----END SSH SIGNATURE-----\n",
            // Cut in the middle of its Base64
            (lines.take(3) + lines.last { it.startsWith("-----END") }).joinToString("\n"),
        )

        for (text in broken) {
            assertInstanceOf(
                SignatureVerdict.Invalid::class.java,
                verifySshSignature(message, text.toByteArray(), SIGNATURE_NAMESPACE, key),
                text,
            )
        }
    }

    @Test
    fun `a public key reads as ssh-keygen wrote it`() {
        val keys = TestKeys(dir)
        val line = keys.publicKeyLine(keys.newKey("mac"))
        val key = Ed25519PublicKey.parse(line)

        assertEquals(line.substringBeforeLast(" "), key.toString())
        assertEquals(key, Ed25519PublicKey.parse(key.toString()))
        assertNotEquals(key, Ed25519PublicKey.parse(keys.publicKeyLine(keys.newKey("other"))))
    }

    @Test
    fun `what isn't an Ed25519 public key is refused`() {
        val keys = TestKeys(dir)
        val rsa = keys.publicKeyLine(keys.newKey("rsa", type = "rsa"))
        val ed25519 = keys.publicKeyLine(keys.newKey("mac")).split(" ")

        for (line in listOf(rsa, "ssh-ed25519", "ssh-ed25519 !!!", "ssh-ed25519 ${ed25519[1].dropLast(8)}", "")) {
            assertThrows(SshFormatException::class.java, { Ed25519PublicKey.parse(line) }, line)
        }
    }

    private fun publicKey(keys: TestKeys, key: File) = Ed25519PublicKey.parse(keys.publicKeyLine(key))

    private fun assertInvalid(verdict: SignatureVerdict, reasonContains: String) {
        assertInstanceOf(SignatureVerdict.Invalid::class.java, verdict)
        val reason = (verdict as SignatureVerdict.Invalid).reason
        assertTrue(reason.contains(reasonContains, ignoreCase = true), reason)
    }
}
