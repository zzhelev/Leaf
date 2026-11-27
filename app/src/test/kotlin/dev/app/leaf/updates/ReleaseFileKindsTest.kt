// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import dev.app.leaf.common.OS
import dev.app.leaf.releasemanifest.ReleaseFileKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/** Which release file updates which copy of Leaf (docs/fork/auto-update.md, section 4). */
class ReleaseFileKindsTest {
    private fun leaf(
        os: OS,
        arch: String,
        jpackageVersion: String? = "1.10.0",
        launcher: String? = null,
        isPackagedJar: Boolean = false,
    ) = RunningLeaf(os, arch, jpackageVersion, launcher, isPackagedJar)

    @Test
    fun `the macOS app on Apple Silicon takes the DMG, and an Intel Mac or a dev run nothing`() {
        assertEquals(ReleaseFileKind.MACOS_ARM64, releaseFileKindOf(leaf(OS.MAC, "aarch64", launcher = "/Applications/Leaf.app/Contents/MacOS/Leaf")))
        assertNull(releaseFileKindOf(leaf(OS.MAC, "x86_64")))
        assertNull(releaseFileKindOf(leaf(OS.MAC, "aarch64", jpackageVersion = null)))
    }

    @Test
    fun `Windows takes the installer where its uninstaller is next to Leaf, and the portable ZIP elsewhere`() {
        // Paths in this system's form: a Windows path has no folder elsewhere
        val installed = File(File("Program Files", "Leaf"), "Leaf.exe").absolutePath
        val uninstaller = File(File(installed).parentFile, "unins000.exe")

        assertEquals(
            ReleaseFileKind.WINDOWS_X64_SETUP,
            releaseFileKindOf(leaf(OS.WINDOWS, "amd64", launcher = installed)) { it == uninstaller },
        )
        assertEquals(
            ReleaseFileKind.WINDOWS_X64_PORTABLE,
            releaseFileKindOf(leaf(OS.WINDOWS, "amd64", launcher = File(File("Tools", "Leaf"), "Leaf.exe").absolutePath)) {
                it == uninstaller
            },
        )
        assertNull(releaseFileKindOf(leaf(OS.WINDOWS, "amd64", launcher = null)) { true })
        assertNull(releaseFileKindOf(leaf(OS.WINDOWS, "amd64", launcher = "Leaf.exe")) { true }, "a launcher without a folder")
        assertNull(releaseFileKindOf(leaf(OS.WINDOWS, "aarch64", launcher = installed)) { true })
        assertNull(releaseFileKindOf(leaf(OS.WINDOWS, "amd64", jpackageVersion = null, launcher = installed)) { true })
    }

    @Test
    fun `Linux takes the package that jpackage's launcher started, or the jar, for its CPU`() {
        assertEquals(ReleaseFileKind.LINUX_AMD64_DEB, releaseFileKindOf(leaf(OS.LINUX, "amd64")))
        assertEquals(ReleaseFileKind.LINUX_ARM64_DEB, releaseFileKindOf(leaf(OS.LINUX, "aarch64")))
        assertEquals(
            ReleaseFileKind.LINUX_X86_64_JAR,
            releaseFileKindOf(leaf(OS.LINUX, "amd64", jpackageVersion = null, isPackagedJar = true)),
        )
        assertEquals(
            ReleaseFileKind.LINUX_AARCH64_JAR,
            releaseFileKindOf(leaf(OS.LINUX, "aarch64", jpackageVersion = null, isPackagedJar = true)),
        )
        assertNull(releaseFileKindOf(leaf(OS.LINUX, "amd64", jpackageVersion = null)))
        assertNull(releaseFileKindOf(leaf(OS.LINUX, "riscv64")))
    }

    @Test
    fun `an unknown system takes nothing, and this test run is a dev run`() {
        assertNull(releaseFileKindOf(leaf(OS.UNKNOWN, "amd64")))
        assertNull(releaseFileKindOf(runningLeaf()))
    }
}
