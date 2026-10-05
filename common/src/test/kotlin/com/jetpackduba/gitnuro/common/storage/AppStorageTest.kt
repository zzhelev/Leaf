package com.jetpackduba.gitnuro.common.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

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
    fun `a JVM without the jpackage launcher property counts as a dev run`() {
        assertEquals(null, System.getProperty("jpackage.app-version"))
        assertEquals(dev, AppStorage.current)
    }
}
