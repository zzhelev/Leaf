// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [parseMergeToolConfig] reads `git config -z --get-regexp`, whose output is `<key>\n<value>\0` per entry. */
class MergeToolConfigTest {
    @Test
    fun `nothing set means no tool, and both settings changed`() {
        val config = parseMergeToolConfig("")

        assertEquals(MergeToolConfig(tool = null, keepBackupSet = false, writeToTempSet = false), config)
        assertEquals(
            listOf("-c", "mergetool.keepBackup=false", "-c", "mergetool.writeToTemp=true"),
            config.overrides(),
        )
    }

    @Test
    fun `merge guitool comes before merge tool, wherever it's set`() {
        assertEquals("meld", parseMergeToolConfig("merge.guitool\nmeld\u0000merge.tool\nvimdiff\u0000").tool)
        assertEquals("meld", parseMergeToolConfig("merge.tool\nvimdiff\u0000merge.guitool\nmeld\u0000").tool)
        assertEquals("vimdiff", parseMergeToolConfig("merge.tool\nvimdiff\u0000").tool)
    }

    @Test
    fun `the last value of a key wins, as for git config`() {
        assertEquals("kdiff3", parseMergeToolConfig("merge.tool\nmeld\u0000merge.tool\nkdiff3\u0000").tool)
    }

    @Test
    fun `an empty tool is no tool, as for git`() {
        assertEquals("vimdiff", parseMergeToolConfig("merge.guitool\n\u0000merge.tool\nvimdiff\u0000").tool)
        assertEquals(null, parseMergeToolConfig("merge.tool\n\u0000").tool)
        // A key without a value (`[merge] tool`)
        assertEquals(null, parseMergeToolConfig("merge.tool\u0000").tool)
    }

    @Test
    fun `settings the user set are left alone, whatever their value`() {
        val config = parseMergeToolConfig("mergetool.keepbackup\ntrue\u0000mergetool.writetotemp\nfalse\u0000")

        assertEquals(MergeToolConfig(tool = null, keepBackupSet = true, writeToTempSet = true), config)
        assertEquals(emptyList<String>(), config.overrides())
        assertEquals(
            listOf("-c", "mergetool.writeToTemp=true"),
            parseMergeToolConfig("mergetool.keepbackup\nfalse\u0000").overrides(),
        )
    }
}
