// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import org.eclipse.jgit.lib.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** git-lfs's settings that ask for the pointer instead of the object, read as git-lfs 3.8 reads them (probed). */
class LfsSmudgeSettingsTest {
    @Test
    fun `GIT_LFS_SKIP_SMUDGE is true for the values that git-lfs takes as true`() {
        val trueValues = listOf("1", "t", "T", "true", "TRUE", "True", "on", "yes", "Yes")
        val falseValues = listOf("", "0", "y", "2", "false", "off", "no", " 1 ", "1 ", " true")

        assertEquals(trueValues.map { true }, trueValues.map { skipsSmudge(it) })
        assertEquals(falseValues.map { false }, falseValues.map { skipsSmudge(it) })
        assertFalse(LfsSmudgeSettings(Config(), emptyMap()).skipsSmudge)
    }

    @Test
    fun `download errors are skipped with lfs skipdownloaderrors, or GIT_LFS_SKIP_DOWNLOAD_ERRORS`() {
        val config = Config()

        assertFalse(LfsSmudgeSettings(config, emptyMap()).skipsDownloadErrors)
        assertTrue(LfsSmudgeSettings(config, mapOf("GIT_LFS_SKIP_DOWNLOAD_ERRORS" to "1")).skipsDownloadErrors)

        config.fromText("[lfs]\n\tskipdownloaderrors = true\n")
        assertTrue(LfsSmudgeSettings(config, emptyMap()).skipsDownloadErrors)

        config.fromText("[lfs]\n\tskipdownloaderrors\n")
        assertTrue(LfsSmudgeSettings(config, emptyMap()).skipsDownloadErrors)

        config.fromText("[lfs]\n\tskipdownloaderrors = false\n")
        assertFalse(LfsSmudgeSettings(config, emptyMap()).skipsDownloadErrors)
    }

    private fun skipsSmudge(value: String) =
        LfsSmudgeSettings(Config(), mapOf("GIT_LFS_SKIP_SMUDGE" to value)).skipsSmudge
}
