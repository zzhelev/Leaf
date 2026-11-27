// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

/**
 * A key that may sign Leaf's releases: its [name], which names its signature file ([signatureFileName]), and its public
 * half as ssh-keygen writes it (`ssh-ed25519 AAAA…`).
 */
data class TrustedKey(val name: String, val publicKey: String)

private val KEY_NAME = Regex("""[a-z0-9][a-z0-9-]*""")

/** A name that can go into a signature file's name: lower-case letters, digits and hyphens. */
fun isKeyName(name: String): Boolean = KEY_NAME.matches(name)

/** Where [TRUSTED_RELEASE_KEYS] are, next to this code: the release workflow's signing script reads the same file. */
const val TRUSTED_KEYS_RESOURCE = "trusted-release-keys"

/**
 * The keys that may sign Leaf's releases (docs/fork/auto-update.md, D1 and D12): the release workflow's, and the offline
 * backup. A key is added, or dropped, by a release that the keys before it signed. A file that can't be read trusts no
 * key, so Leaf updates from nothing.
 */
val TRUSTED_RELEASE_KEYS: List<TrustedKey> by lazy { trustedKeysFile()?.let(::parseTrustedKeys) ?: emptyList() }

/** The text of Leaf's trusted keys file, or null when it isn't there. */
internal fun trustedKeysFile(): String? = TrustedKey::class.java.getResourceAsStream(TRUSTED_KEYS_RESOURCE)
    ?.use { it.readBytes().toString(Charsets.UTF_8) }

/**
 * The keys in a trusted keys file: one a line, its name, then its public half as ssh-keygen writes it (`ssh-ed25519
 * AAAA… comment`). A line whose first word can't name a signature file is left out: blank lines, and comments, as `#`
 * starts no name.
 */
fun parseTrustedKeys(text: String): List<TrustedKey> = text.lineSequence()
    .map { it.trim() }
    .mapNotNull { line ->
        val parts = line.split(Regex("""\s+"""))

        if (parts.size >= 3 && isKeyName(parts[0])) TrustedKey(parts[0], "${parts[1]} ${parts[2]}") else null
    }
    .toList()

/** How many of [TRUSTED_RELEASE_KEYS] must have signed a release's manifest, each a different key. */
const val REQUIRED_SIGNATURES = 1

/** What checking a manifest's signatures found. */
data class SignatureCheck(
    /** The keys whose signatures checked out, each a different key, in the order of the trusted keys. */
    val signedBy: List<String>,
    /** Why each other trusted key's signature didn't count, by the key's name. */
    val problems: Map<String, String>,
    val required: Int,
) {
    val isEnough: Boolean get() = signedBy.size >= required
}

/**
 * Checks [signatures] of [manifest], by key name: the file named after a key ([signatureFileName]) must hold that key's
 * signature. A key that two names share counts once. At least one signature is always required, whatever [required]
 * says.
 */
fun checkReleaseSignatures(
    manifest: ByteArray,
    signatures: Map<String, ByteArray?>,
    trusted: List<TrustedKey> = TRUSTED_RELEASE_KEYS,
    required: Int = REQUIRED_SIGNATURES,
): SignatureCheck {
    val signedBy = mutableListOf<String>()
    val problems = linkedMapOf<String, String>()
    val countedKeys = mutableMapOf<Ed25519PublicKey, String>()

    for (trustedKey in trusted) {
        val name = trustedKey.name
        val signature = signatures[name]

        if (signature == null) {
            problems[name] = "No signature"
            continue
        }

        val key = try {
            Ed25519PublicKey.parse(trustedKey.publicKey)
        } catch (e: SshFormatException) {
            problems[name] = "The trusted key can't be read: ${e.message}"
            continue
        }

        when (val verdict = verifySshSignature(manifest, signature, SIGNATURE_NAMESPACE, key)) {
            SignatureVerdict.Valid -> {
                val countedAs = countedKeys[key]

                if (countedAs != null) {
                    problems[name] = "The same key as $countedAs"
                } else {
                    countedKeys[key] = name
                    signedBy += name
                }
            }

            is SignatureVerdict.Invalid -> problems[name] = verdict.reason
        }
    }

    return SignatureCheck(signedBy, problems, required.coerceAtLeast(1))
}
