// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The GitHub repository whose releases Leaf updates from. */
const val RELEASES_REPOSITORY = "zzhelev/Leaf"

/** Where a release's files are downloaded from: `<this>/leaf-<version>/<file>`. */
const val RELEASE_DOWNLOADS = "https://github.com/$RELEASES_REPOSITORY/releases/download"

const val MANIFEST_FILE_NAME = "leaf-update.json"

/** The manifest format that this code writes and reads. */
const val MANIFEST_FORMAT = 1

/** ssh-keygen's namespace for release signatures, so that a signature made for anything else doesn't pass as one. */
const val SIGNATURE_NAMESPACE = "leaf-release"

private val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")

/** A version as Leaf's releases have them: three numbers. Anything else is refused before it goes into an address. */
fun isReleaseVersion(text: String): Boolean = RELEASE_VERSION.matches(text)

fun releaseTag(version: String): String = "leaf-$version"

fun releasePage(version: String): String = "https://github.com/$RELEASES_REPOSITORY/releases/tag/${releaseTag(version)}"

fun releaseFileUrl(version: String, fileName: String): String = "$RELEASE_DOWNLOADS/${releaseTag(version)}/$fileName"

/** The file of the signature that the key called [keyName] made of a release's manifest. */
fun signatureFileName(keyName: String): String = "$MANIFEST_FILE_NAME.$keyName.sig"

/** A file of a release that Leaf can update itself from, by the key that names it in the manifest. */
enum class ReleaseFileKind(val key: String, private val namePattern: String) {
    MACOS_ARM64("macos-arm64", "Leaf-%s-macos-arm64.dmg"),
    WINDOWS_X64_SETUP("windows-x64-setup", "Leaf-%s-windows-x64-setup.exe"),
    WINDOWS_X64_PORTABLE("windows-x64-portable", "Leaf-%s-windows-x64-portable.zip"),
    LINUX_X86_64_JAR("linux-x86_64-jar", "Leaf-%s-linux-x86_64.jar"),
    LINUX_AARCH64_JAR("linux-aarch64-jar", "Leaf-%s-linux-arm_aarch64.jar"),
    LINUX_AMD64_DEB("linux-amd64-deb", "Leaf-%s-linux-amd64.deb"),
    LINUX_ARM64_DEB("linux-arm64-deb", "Leaf-%s-linux-arm64.deb");

    /** The file's name in the release, as the release workflow names it. */
    fun fileName(version: String): String = namePattern.format(version)

    companion object {
        fun ofKey(key: String): ReleaseFileKind? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
data class ReleaseFile(
    val name: String,
    val size: Long,
    /** Lower-case hex. */
    val sha256: String,
)

/**
 * What a release holds, which the keys of [TRUSTED_RELEASE_KEYS] sign: the version, its code, and each file's size and
 * SHA-256, by [ReleaseFileKind.key]. The release workflow writes it with `release-manifest/sign-manifest.sh`.
 */
@Serializable
data class ReleaseManifest(
    val format: Int,
    val appVersion: String,
    val appCode: Int,
    val releasePage: String,
    val files: Map<String, ReleaseFile>,
) {
    fun fileFor(kind: ReleaseFileKind): ReleaseFile? = files[kind.key]

    companion object {
        /** Reads a manifest whose signatures checked out. Fields that a later format adds are left out. */
        fun parse(bytes: ByteArray): ReleaseManifest =
            readingJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}

private val readingJson = Json { ignoreUnknownKeys = true }
