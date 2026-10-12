// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.api.MergeCommand.ConflictStyle
import org.eclipse.jgit.api.MergeCommand.FastForwardMode
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm
import org.eclipse.jgit.lib.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The values of `merge.conflictStyle` and `diff.algorithm` that git accepts and JGit's enums lack, read through
 * `Config.getEnum` as JGit's merges and diffs read them.
 */
class GitCompatibleConfigGetterTest {
    @BeforeEach
    fun installGetter() {
        readGitOnlyConfigValues()
    }

    @AfterEach
    fun restoreJGitGetter() {
        Config.setTypedConfigGetter(null)
    }

    @Test
    fun `zdiff3 is read as diff3`() {
        assertEquals(ConflictStyle.DIFF3, conflictStyle("zdiff3"))
        assertEquals(ConflictStyle.DIFF3, conflictStyle("ZDiff3"))
    }

    @Test
    fun `the conflict styles that JGit knows are read as JGit reads them`() {
        assertEquals(ConflictStyle.DIFF3, conflictStyle("diff3"))
        assertEquals(ConflictStyle.MERGE, conflictStyle("merge"))
        assertEquals(ConflictStyle.MERGE, conflictStyle(null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "minimal", "Minimal"])
    fun `git's other names for myers are read as myers`(value: String) {
        assertEquals(SupportedAlgorithm.MYERS, diffAlgorithm(value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "PATIENCE"])
    fun `patience is read as histogram`(value: String) {
        assertEquals(SupportedAlgorithm.HISTOGRAM, diffAlgorithm(value))
    }

    @Test
    fun `the diff algorithms that JGit knows are read as JGit reads them`() {
        assertEquals(SupportedAlgorithm.MYERS, diffAlgorithm("myers"))
        assertEquals(SupportedAlgorithm.HISTOGRAM, diffAlgorithm("histogram"))
        assertEquals(SupportedAlgorithm.HISTOGRAM, diffAlgorithm(null))
    }

    @Test
    fun `the keys are found in any case, as config keys are`() {
        val config = Config().apply { fromText("[merge]\n\tconflictStyle = zdiff3\n[diff]\n\talgorithm = patience\n") }

        assertEquals(ConflictStyle.DIFF3, config.getEnum("Merge", null, "conflictstyle", ConflictStyle.MERGE))
        assertEquals(SupportedAlgorithm.HISTOGRAM, config.getEnum("DIFF", null, "Algorithm", SupportedAlgorithm.MYERS))
    }

    @Test
    fun `values that git refuses still fail with JGit's message`() {
        assertEquals(
            "Invalid value: merge.conflictStyle=bogus",
            assertThrows(IllegalArgumentException::class.java) { conflictStyle("bogus") }.message,
        )
        assertEquals(
            "Invalid value: diff.algorithm=bogus",
            assertThrows(IllegalArgumentException::class.java) { diffAlgorithm("bogus") }.message,
        )
    }

    @Test
    fun `other keys with these values aren't read differently`() {
        val config = Config().apply {
            fromText(
                "[merge]\n\tff = zdiff3\n\tother = zdiff3\n[diff]\n\tother = patience\n" +
                    "[merge \"x\"]\n\tconflictStyle = zdiff3\n[diff \"x\"]\n\talgorithm = patience\n"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum(FastForwardMode.Merge.values(), "merge", null, "ff", FastForwardMode.Merge.TRUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("merge", null, "other", ConflictStyle.MERGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("diff", null, "other", SupportedAlgorithm.HISTOGRAM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("merge", "x", "conflictStyle", ConflictStyle.MERGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("diff", "x", "algorithm", SupportedAlgorithm.HISTOGRAM)
        }
    }

    @Test
    fun `these keys read as another enum aren't read differently`() {
        val config = Config().apply { fromText("[merge]\n\tconflictStyle = zdiff3\n[diff]\n\talgorithm = patience\n") }

        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("merge", null, "conflictStyle", SupportedAlgorithm.HISTOGRAM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.getEnum("diff", null, "algorithm", ConflictStyle.MERGE)
        }
    }

    /** When this fails, JGit reads these values itself, and [GitCompatibleConfigGetter] can go. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "merge.conflictStyle=zdiff3", "diff.algorithm=patience", "diff.algorithm=default", "diff.algorithm=minimal",
        ]
    )
    fun `JGit's own reader refuses them`(setting: String) {
        Config.setTypedConfigGetter(null)
        val (key, value) = setting.split("=")

        val error = assertThrows(IllegalArgumentException::class.java) {
            if (key == "merge.conflictStyle") conflictStyle(value) else diffAlgorithm(value)
        }

        assertEquals("Invalid value: $setting", error.message)
    }

    private fun conflictStyle(value: String?): ConflictStyle =
        config("merge", "conflictStyle", value).getEnum("merge", null, "conflictStyle", ConflictStyle.MERGE)

    private fun diffAlgorithm(value: String?): SupportedAlgorithm =
        config("diff", "algorithm", value).getEnum("diff", null, "algorithm", SupportedAlgorithm.HISTOGRAM)

    private fun config(section: String, name: String, value: String?) = Config().apply {
        if (value != null) {
            setString(section, null, name, value)
        }
    }
}
