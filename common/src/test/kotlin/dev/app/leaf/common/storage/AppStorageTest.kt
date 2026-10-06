// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.common.storage

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppStorageTest {
    private val packaged = AppStorage.forApp(isPackagedApp = true)
    private val dev = AppStorage.forApp(isPackagedApp = false)

    @Test
    fun `packaged app uses the Leaf names`() {
        assertEquals("LeafConfig", packaged.preferencesNode)
        assertEquals("leaf", packaged.directoryName)
        assertEquals("io.github.zzhelev.leaf", packaged.macLogsDirectoryName)
    }

    @Test
    fun `dev runs share no name with the packaged app`() {
        assertNotEquals(packaged.preferencesNode, dev.preferencesNode)
        assertNotEquals(packaged.directoryName, dev.directoryName)
        assertNotEquals(packaged.macLogsDirectoryName, dev.macLogsDirectoryName)
    }

    @Test
    fun `no name refers to Gitnuro`() {
        val names = listOf(packaged, dev).flatMap {
            listOf(it.preferencesNode, it.directoryName, it.macLogsDirectoryName)
        } + AppStorage.LOG_FILE_NAME + AppStorage.REPOSITORY_CONFIG_FILE_NAME

        names.forEach { name ->
            assertFalse(name.contains("gitnuro", ignoreCase = true), "\"$name\" refers to Gitnuro")
        }
    }

    @Test
    fun `a JVM without the jpackage launcher property, loading classes from folders, counts as a dev run`() {
        assertEquals(null, System.getProperty("jpackage.app-version"))
        assertEquals(dev, AppStorage.current)
    }

    @Test
    fun `a jar whose manifest has Leaf-Packaged true counts as packaged`() {
        assertTrue(AppStorage.isPackagedJar(jarWithManifest(PACKAGED_JAR_ATTRIBUTE to "true")))
    }

    @Test
    fun `a jar without the attribute, or with another value, doesn't count as packaged`() {
        assertFalse(AppStorage.isPackagedJar(jarWithManifest("Main-Class" to "dev.app.leaf.MainKt")))
        assertFalse(AppStorage.isPackagedJar(jarWithManifest(PACKAGED_JAR_ATTRIBUTE to "false")))
        assertFalse(AppStorage.isPackagedJar(jarWithManifest()))
    }

    @Test
    fun `a folder, a missing file or a file that isn't a jar doesn't count as packaged`() {
        val folder = createTempDirectory("leaf-storage-test").toFile().apply { deleteOnExit() }
        val notAJar = File.createTempFile("leaf-storage-test", ".jar").apply {
            writeText("not a jar")
            deleteOnExit()
        }

        assertFalse(AppStorage.isPackagedJar(folder))
        assertFalse(AppStorage.isPackagedJar(File(folder, "missing.jar")))
        assertFalse(AppStorage.isPackagedJar(notAJar))
        assertFalse(AppStorage.isPackagedJar(null))
    }

    private fun jarWithManifest(vararg attributes: Pair<String, String>): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            attributes.forEach { (name, value) -> mainAttributes[Attributes.Name(name)] = value }
        }
        val jar = File.createTempFile("leaf-storage-test", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream(), manifest).use { }
        return jar
    }
}
