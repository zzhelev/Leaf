// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

private const val ED25519 = "ssh-ed25519"
private const val ED25519_KEY_SIZE = 32
private const val ED25519_SIGNATURE_SIZE = 64

/** The DER of an Ed25519 key's SubjectPublicKeyInfo before the key's 32 bytes (RFC 8410). */
private val X509_ED25519_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

private const val SSHSIG_MAGIC = "SSHSIG"
private const val SSHSIG_VERSION = 1
private const val SSHSIG_HASH = "sha512"
private const val ARMOR_BEGIN = "-----BEGIN SSH SIGNATURE-----"
private const val ARMOR_END = "-----END SSH SIGNATURE-----"

/** An Ed25519 public key, as OpenSSH writes it. Equal keys have the same 32 bytes. */
class Ed25519PublicKey private constructor(private val raw: ByteArray) {
    val javaKey: PublicKey by lazy {
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_ED25519_PREFIX + raw))
    }

    override fun equals(other: Any?): Boolean = other is Ed25519PublicKey && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "$ED25519 ${Base64.getEncoder().encodeToString(blob())}"

    private fun blob(): ByteArray = SshWriter().string(ED25519.toByteArray()).string(raw).bytes()

    companion object {
        /** Reads a line of a `.pub` file or of `allowed_signers`: `ssh-ed25519 <base64> [comment]`. */
        fun parse(line: String): Ed25519PublicKey {
            val parts = line.trim().split(Regex("""\s+"""))

            if (parts.size < 2 || parts[0] != ED25519) {
                throw SshFormatException("Not an Ed25519 public key (ssh-ed25519 …)")
            }

            val blob = try {
                Base64.getDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                throw SshFormatException("The key isn't Base64")
            }

            return fromBlob(blob)
        }

        internal fun fromBlob(blob: ByteArray): Ed25519PublicKey {
            val reader = SshReader(blob)

            if (reader.text() != ED25519) {
                throw SshFormatException("Not an Ed25519 key")
            }

            val raw = reader.bytes()
            reader.end()

            if (raw.size != ED25519_KEY_SIZE) {
                throw SshFormatException("An Ed25519 key has $ED25519_KEY_SIZE bytes, not ${raw.size}")
            }

            return Ed25519PublicKey(raw)
        }
    }
}

class SshFormatException(message: String) : Exception(message)

/** What checking one signature found. */
sealed interface SignatureVerdict {
    data object Valid : SignatureVerdict

    data class Invalid(val reason: String) : SignatureVerdict
}

/**
 * Checks a signature that `ssh-keygen -Y sign -n <namespace>` made of [message] (OpenSSH's PROTOCOL.sshsig), made with
 * [key], an Ed25519 key, over a SHA-512 hash. Only the JDK's own Ed25519 is used, so the check needs no ssh-keygen.
 */
fun verifySshSignature(
    message: ByteArray,
    armored: ByteArray,
    namespace: String,
    key: Ed25519PublicKey,
): SignatureVerdict = try {
    val reader = SshReader(unarmor(armored))

    if (!reader.raw(SSHSIG_MAGIC.length).contentEquals(SSHSIG_MAGIC.toByteArray())) {
        throw SshFormatException("Not an SSH signature")
    }

    val version = reader.uint32()

    if (version != SSHSIG_VERSION) {
        throw SshFormatException("An SSH signature of version $version")
    }

    val signedBy = Ed25519PublicKey.fromBlob(reader.bytes())
    val signedNamespace = reader.bytes()
    val reserved = reader.bytes()
    val hash = reader.text()
    val signatureBlob = reader.bytes()
    reader.end()

    val signatureReader = SshReader(signatureBlob)

    if (signatureReader.text() != ED25519) {
        throw SshFormatException("Not an Ed25519 signature")
    }

    val signature = signatureReader.bytes()
    signatureReader.end()

    when {
        signedBy != key -> SignatureVerdict.Invalid("Made with another key")
        !signedNamespace.contentEquals(namespace.toByteArray()) -> SignatureVerdict.Invalid(
            "Made for “${signedNamespace.toString(Charsets.UTF_8)}”, not “$namespace”"
        )

        hash != SSHSIG_HASH -> SignatureVerdict.Invalid("Over a $hash hash, not $SSHSIG_HASH")
        signature.size != ED25519_SIGNATURE_SIZE -> SignatureVerdict.Invalid("An Ed25519 signature of ${signature.size} bytes")
        else -> {
            val signedData = SshWriter()
                .raw(SSHSIG_MAGIC.toByteArray())
                .string(signedNamespace)
                .string(reserved)
                .string(hash.toByteArray())
                .string(MessageDigest.getInstance("SHA-512").digest(message))
                .bytes()

            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key.javaKey)
            verifier.update(signedData)

            if (verifier.verify(signature)) {
                SignatureVerdict.Valid
            } else {
                SignatureVerdict.Invalid("Doesn't match the file")
            }
        }
    }
} catch (e: SshFormatException) {
    SignatureVerdict.Invalid(e.message ?: "Not an SSH signature")
} catch (e: GeneralSecurityException) {
    SignatureVerdict.Invalid("Couldn't be checked: ${e.message}")
}

private fun unarmor(armored: ByteArray): ByteArray {
    val lines = armored.toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
    val begin = lines.indexOf(ARMOR_BEGIN)
    val end = lines.indexOf(ARMOR_END)

    if (begin < 0 || end < begin) {
        throw SshFormatException("Not an SSH signature (no BEGIN and END lines)")
    }

    return try {
        Base64.getDecoder().decode(lines.subList(begin + 1, end).joinToString(""))
    } catch (_: IllegalArgumentException) {
        throw SshFormatException("The signature isn't Base64")
    }
}

/** Reads SSH's wire format (RFC 4251): big-endian uint32, and strings that start with their length. */
private class SshReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes)

    fun uint32(): Int {
        if (buffer.remaining() < 4) throw SshFormatException("Cut short")
        return buffer.getInt()
    }

    fun raw(count: Int): ByteArray {
        if (count < 0 || buffer.remaining() < count) throw SshFormatException("Cut short")
        return ByteArray(count).also { buffer.get(it) }
    }

    fun bytes(): ByteArray = raw(uint32())

    fun text(): String = bytes().toString(Charsets.UTF_8)

    fun end() {
        if (buffer.hasRemaining()) throw SshFormatException("Has more than it should")
    }
}

private class SshWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    fun string(bytes: ByteArray) = apply {
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
    }

    fun bytes(): ByteArray = out.toByteArray()
}
