// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.editor

import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.InstalledEditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Finding the known editors on a computer (fork-only), in folders laid out as each system has them. */
class ExternalEditorsTest {
    @TempDir
    lateinit var temp: File

    private fun app(folder: File, name: String, bundleId: String, vararg tools: String): File {
        val app = File(folder, "$name.app")
        File(app, "Contents").mkdirs()
        File(app, "Contents/Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>CFBundleExecutable</key>
                <string>$name</string>
                <key>CFBundleIdentifier</key>
                <string>$bundleId</string>
            </dict>
            </plist>
            """.trimIndent()
        )
        for (tool in tools) {
            File(app, tool).apply {
                parentFile.mkdirs()
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
        }
        return app
    }

    @Test
    fun `macOS apps are found by bundle id, with their tools, in the order of the known editors`() {
        val applications = File(temp, "Applications").apply { mkdirs() }
        val vsCode = app(applications, "Visual Studio Code", "com.microsoft.VSCode", "Contents/Resources/app/bin/code")
        val intellij = app(applications, "IntelliJ IDEA", "com.jetbrains.intellij")
        app(applications, "Notes", "com.apple.Notes")

        val editors = macEditors(listOf(applications), ::bundleIdOf)

        assertEquals(
            listOf(
                InstalledEditor(
                    "vscode", "Visual Studio Code", EditorKind.VS_CODE,
                    tool = File(vsCode, "Contents/Resources/app/bin/code").path,
                    macApp = vsCode.path,
                ),
                InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = null, macApp = intellij.path),
            ),
            editors,
        )
    }

    @Test
    fun `an app renamed by the user is found by its bundle id, and one in a folder of apps too`() {
        val applications = File(temp, "Applications").apply { mkdirs() }
        val toolbox = File(applications, "JetBrains Toolbox").apply { mkdirs() }
        app(applications, "My Code", "com.microsoft.VSCode")
        app(toolbox, "PyCharm", "com.jetbrains.pycharm")

        assertEquals(listOf("vscode", "pycharm"), macEditors(listOf(applications), ::bundleIdOf).map { it.id })
    }

    @Test
    fun `the first folder that has an editor wins, and a tool that isn't executable doesn't count`() {
        val system = File(temp, "system").apply { mkdirs() }
        val user = File(temp, "user").apply { mkdirs() }
        val first = app(system, "Zed", "dev.zed.Zed")
        app(user, "Zed", "dev.zed.Zed", "Contents/MacOS/cli")
        File(first, "Contents/MacOS").mkdirs()
        File(first, "Contents/MacOS/cli").writeText("not executable")

        val zed = macEditors(listOf(system, user), ::bundleIdOf).single()

        assertEquals(first.path, zed.macApp)
        assertNull(zed.tool)
    }

    @Test
    fun `an app's icon file is the one its plist names, with or without its extension`() {
        val applications = File(temp, "Applications").apply { mkdirs() }
        val zed = app(applications, "Zed", "dev.zed.Zed")
        File(zed, "Contents/Info.plist").writeText(
            File(zed, "Contents/Info.plist").readText()
                .replace("</dict>", "<key>CFBundleIconFile</key><string>Zed</string></dict>")
        )
        val icon = File(zed, "Contents/Resources/Zed.icns").apply {
            parentFile.mkdirs()
            writeText("icns")
        }

        assertEquals(icon.path, macEditors(listOf(applications), ::bundleIdOf).single().icon)

        icon.delete()
        assertNull(iconFileOf(zed), "named, and not there")
    }

    @Test
    fun `a folder that doesn't exist and an app without a plist are skipped`() {
        val applications = File(temp, "Applications").apply { mkdirs() }
        File(applications, "Broken.app").mkdirs()

        assertEquals(emptyList<InstalledEditor>(), macEditors(listOf(File(temp, "missing"), applications), ::bundleIdOf))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a binary plist is read with plutil, as Xcode's is`() {
        val app = app(temp, "Xcode", "com.apple.dt.Xcode")
        val plist = File(app, "Contents/Info.plist")
        val converted = ProcessBuilder("/usr/bin/plutil", "-convert", "binary1", plist.path).start().waitFor()

        assertEquals(0, converted)
        assertEquals("bplist", plist.readBytes().decodeToString(0, 6))
        assertEquals("com.apple.dt.Xcode", bundleIdOf(app))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `on Linux, commands are found on the PATH and in JetBrains Toolbox's scripts`() {
        val bin = File(temp, "bin").apply { mkdirs() }
        val scripts = File(temp, "scripts").apply { mkdirs() }
        fun executable(folder: File, name: String) = File(folder, name).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        executable(bin, "code")
        executable(scripts, "idea")
        executable(bin, "zeditor")
        File(bin, "subl").writeText("not executable")

        val editors = posixEditors(listOf(bin.path, "", scripts.path))

        assertEquals(listOf("vscode", "zed", "intellij-idea"), editors.map { it.id })
        assertEquals(File(bin, "zeditor").path, editors[1].tool)
    }

    @Test
    fun `on Windows, an installer's folder comes before the PATH, which takes cmd and exe`() {
        val local = File(temp, "Local").apply { mkdirs() }
        val path = File(temp, "path").apply { mkdirs() }
        // The installer's path as Windows writes it: elsewhere, one file whose name has the backslashes
        val code = "${local.path}\\Programs\\Microsoft VS Code\\bin\\code.cmd"
        File(code).apply {
            parentFile.mkdirs()
            writeText("")
        }
        File(path, "code.cmd").writeText("")
        File(path, "cursor.exe").writeText("")

        val editors = windowsEditors(mapOf("LocalAppData" to local.path), path.path)

        assertEquals(listOf("vscode", "cursor"), editors.map { it.id })
        assertEquals(File(code).path, editors[0].tool)
        assertEquals(File(path, "cursor.exe").path, editors[1].tool)
    }

    @Test
    fun `Windows variables expand in any case, and a path with one that isn't set is none`() {
        assertEquals("C:\\Users\\me\\x", expandWindowsVariables("%localappdata%\\x", mapOf("LOCALAPPDATA" to "C:\\Users\\me")))
        assertNull(expandWindowsVariables("%PROGRAMFILES%\\x", emptyMap()))
    }
}
