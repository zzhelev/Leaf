// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.releasemanifest.ReleaseFileKind
import java.io.File

/** What runs this Leaf, as far as updating it goes (docs/fork/auto-update.md, section 4). */
data class RunningLeaf(
    val os: OS,
    /** `os.arch`: `aarch64`, or `amd64` or `x86_64` for the same CPU. */
    val arch: String,
    /** `jpackage.app-version`, which only jpackage's launchers set: the macOS and Windows apps and the Linux `.deb`. */
    val jpackageVersion: String?,
    /** `jpackage.app-path`, the launcher that started Leaf, such as `C:\Program Files\Leaf\Leaf.exe`. */
    val launcher: String?,
    /** Whether Leaf runs from the Linux jar, whose manifest marks it as packaged. */
    val isPackagedJar: Boolean,
)

/** Inno Setup's uninstaller, which only an installed Windows copy has next to `Leaf.exe`. */
private const val WINDOWS_UNINSTALLER = "unins000.exe"

/**
 * The file of a release that updates [leaf], or null when none can: a dev run, a system without a release file (an
 * Intel Mac, 32-bit Windows), or a Windows copy whose launcher isn't known.
 */
fun releaseFileKindOf(leaf: RunningLeaf, exists: (File) -> Boolean = File::exists): ReleaseFileKind? {
    val isPackaged = leaf.jpackageVersion != null
    val isArm = leaf.arch == "aarch64"
    val isX64 = leaf.arch == "amd64" || leaf.arch == "x86_64"

    return when (leaf.os) {
        OS.MAC -> ReleaseFileKind.MACOS_ARM64.takeIf { isPackaged && isArm }
        OS.WINDOWS -> {
            val folder = leaf.launcher?.let { File(it).parentFile }

            when {
                !isPackaged || !isX64 || folder == null -> null
                exists(File(folder, WINDOWS_UNINSTALLER)) -> ReleaseFileKind.WINDOWS_X64_SETUP
                else -> ReleaseFileKind.WINDOWS_X64_PORTABLE
            }
        }

        OS.LINUX -> when {
            isPackaged && isX64 -> ReleaseFileKind.LINUX_AMD64_DEB
            isPackaged && isArm -> ReleaseFileKind.LINUX_ARM64_DEB
            leaf.isPackagedJar && isX64 -> ReleaseFileKind.LINUX_X86_64_JAR
            leaf.isPackagedJar && isArm -> ReleaseFileKind.LINUX_AARCH64_JAR
            else -> null
        }

        OS.UNKNOWN -> null
    }
}

/** This Leaf, from the JVM's properties and the jar that its classes come from. */
fun runningLeaf(): RunningLeaf {
    val codeSource = RunningLeaf::class.java.protectionDomain?.codeSource?.location
        ?.let { runCatching { File(it.toURI()) }.getOrNull() }

    return RunningLeaf(
        os = currentOs,
        arch = System.getProperty("os.arch").orEmpty(),
        jpackageVersion = System.getProperty("jpackage.app-version"),
        launcher = System.getProperty("jpackage.app-path"),
        isPackagedJar = AppStorage.isPackagedJar(codeSource),
    )
}
