// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseManifestTest {
    private val files = ReleaseFileKind.entries.associate { kind ->
        kind.key to ReleaseFile(kind.fileName("1.11.0"), 100L + kind.ordinal, "ab".repeat(32))
    }

    private fun manifest(files: Map<String, ReleaseFile>) =
        ReleaseManifest(MANIFEST_FORMAT, "1.11.0", 38, releasePage("1.11.0"), files)

    // As sign-manifest.sh writes it, cut to two files
    private val written = """
        {
            "format": 1,
            "appVersion": "1.11.0",
            "appCode": 38,
            "releasePage": "https://github.com/zzhelev/Leaf/releases/tag/leaf-1.11.0",
            "files": {
                "linux-aarch64-jar": {
                    "name": "Leaf-1.11.0-linux-arm_aarch64.jar",
                    "size": 120,
                    "sha256": "${"ab".repeat(32)}"
                },
                "macos-arm64": {
                    "name": "Leaf-1.11.0-macos-arm64.dmg",
                    "size": 182,
                    "sha256": "${"cd".repeat(32)}"
                }
            }
        }
    """.trimIndent() + "\n"

    private val read = manifest(
        mapOf(
            "linux-aarch64-jar" to ReleaseFile("Leaf-1.11.0-linux-arm_aarch64.jar", 120, "ab".repeat(32)),
            "macos-arm64" to ReleaseFile("Leaf-1.11.0-macos-arm64.dmg", 182, "cd".repeat(32)),
        )
    )

    @Test
    fun `a manifest reads as it was written`() {
        assertEquals(read, ReleaseManifest.parse(written.toByteArray()))
    }

    @Test
    fun `fields that a later format adds are left out`() {
        val later = written.replaceFirst("{", "{\n    \"notes\": \"Something new\",")

        assertEquals(read, ReleaseManifest.parse(later.toByteArray()))
    }

    @Test
    fun `each kind is named as the release workflow names its file`() {
        // The files of release leaf-1.10.0, from GitHub
        val released = setOf(
            "Leaf-1.10.0-linux-amd64.deb",
            "Leaf-1.10.0-linux-arm64.deb",
            "Leaf-1.10.0-linux-arm_aarch64.jar",
            "Leaf-1.10.0-linux-x86_64.jar",
            "Leaf-1.10.0-macos-arm64.dmg",
            "Leaf-1.10.0-windows-x64-portable.zip",
            "Leaf-1.10.0-windows-x64-setup.exe",
        )

        assertEquals(released, ReleaseFileKind.entries.map { it.fileName("1.10.0") }.toSet())
    }

    @Test
    fun `a kind is found by its key`() {
        assertEquals(ReleaseFileKind.LINUX_AARCH64_JAR, ReleaseFileKind.ofKey("linux-aarch64-jar"))
        assertNull(ReleaseFileKind.ofKey("macos-x64"))
        assertEquals(files["macos-arm64"], manifest(files).fileFor(ReleaseFileKind.MACOS_ARM64))
    }

    @Test
    fun `only three numbers are a release version`() {
        assertTrue(isReleaseVersion("1.10.0"))
        assertTrue(isReleaseVersion("12.0.345"))

        for (text in listOf("1.10", "1.10.0-beta", "v1.10.0", "1.10.0/", "../1.10.0", "1.10.0\n", "", "1.x.0")) {
            assertFalse(isReleaseVersion(text), text)
        }
    }

    @Test
    fun `the addresses of a release`() {
        assertEquals("leaf-1.11.0", releaseTag("1.11.0"))
        assertEquals(
            "https://github.com/zzhelev/Leaf/releases/download/leaf-1.11.0/leaf-update.json",
            releaseFileUrl("1.11.0", MANIFEST_FILE_NAME),
        )
        assertEquals("https://github.com/zzhelev/Leaf/releases/tag/leaf-1.11.0", releasePage("1.11.0"))
        assertEquals("leaf-update.json.mac.sig", signatureFileName("mac"))
    }
}
