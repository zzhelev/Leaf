// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val DAY = 24 * 60 * 60 * 1000L
private const val NOW = 1_800_000_000_000L

private fun ref(name: String, daysAgo: Int? = null) =
    RefEntry(item = name, key = "refs/heads/$name", name = name, date = daysAgo?.let { NOW - it * DAY })

private fun List<RefEntry<String>>.names() = map { it.name }

private fun List<RefRow<String>>.labels() = map { row ->
    when (row) {
        is RefRow.Folder -> "[${row.label}]"
        is RefRow.Item -> "  ".repeat(row.depth) + row.displayName
    }
}

private val allExpanded: (String, Boolean) -> Boolean = { _, _ -> true }

class RefRowsTest {
    private val branches = listOf(
        ref("master", daysAgo = 13),
        ref("feature/CAPS-1249-5-free-archive-space", daysAgo = 1),
        ref("develop", daysAgo = 8),
        ref("bugfix/CAPS-700-hypermarket-reopen", daysAgo = 62),
        ref("feature/CAPS-1034-migrate-ml-pipeline-hilt", daysAgo = 30),
        ref("feature/CAPS-656-pallet-crop", daysAgo = 180),
        ref("hotfix/v3.0.4", daysAgo = 195),
    )

    @Test
    fun `sorts by name A to Z and Z to A with numbers compared as numbers`() {
        val ascending = sortRefs(branches, RefSortState(RefSortKey.Name, true), headName = null, keepHeadOnTop = false)

        assertEquals(
            listOf(
                "bugfix/CAPS-700-hypermarket-reopen",
                "develop",
                "feature/CAPS-656-pallet-crop",
                "feature/CAPS-1034-migrate-ml-pipeline-hilt",
                "feature/CAPS-1249-5-free-archive-space",
                "hotfix/v3.0.4",
                "master",
            ),
            ascending.names(),
        )

        val descending = sortRefs(branches, RefSortState(RefSortKey.Name, false), headName = null, keepHeadOnTop = false)

        assertEquals(ascending.names().reversed(), descending.names())
    }

    @Test
    fun `sorts by last commit newest or oldest first`() {
        val newest = sortRefs(branches, RefSortState(RefSortKey.LastCommit, true), null, false)

        assertEquals(
            listOf(
                "feature/CAPS-1249-5-free-archive-space",
                "develop",
                "master",
                "feature/CAPS-1034-migrate-ml-pipeline-hilt",
                "bugfix/CAPS-700-hypermarket-reopen",
                "feature/CAPS-656-pallet-crop",
                "hotfix/v3.0.4",
            ),
            newest.names(),
        )

        val oldest = sortRefs(branches, RefSortState(RefSortKey.LastCommit, false), null, false)

        assertEquals(newest.names().reversed(), oldest.names())
    }

    @Test
    fun `sorts tags by tag date`() {
        val tags = listOf(ref("3.1.0", 130), ref("3.3.0", 15), ref("3.2.0", 70))

        assertEquals(
            listOf("3.3.0", "3.2.0", "3.1.0"),
            sortRefs(tags, RefSortState(RefSortKey.TagDate, true), null, false).names(),
        )
        assertEquals(
            listOf("3.1.0", "3.2.0", "3.3.0"),
            sortRefs(tags, RefSortState(RefSortKey.TagDate, false), null, false).names(),
        )
    }

    @Test
    fun `branches never checked out go last in both orders, sorted by name`() {
        val refs = listOf(ref("zeta"), ref("main", 2), ref("alpha"), ref("develop", 5))

        assertEquals(
            listOf("main", "develop", "alpha", "zeta"),
            sortRefs(refs, RefSortState(RefSortKey.LastCheckout, true), null, false).names(),
        )
        assertEquals(
            listOf("develop", "main", "alpha", "zeta"),
            sortRefs(refs, RefSortState(RefSortKey.LastCheckout, false), null, false).names(),
        )
    }

    @Test
    fun `ties on date are broken by name A to Z in both orders`() {
        val refs = listOf(ref("b", 3), ref("a", 3), ref("c", 1))

        assertEquals(listOf("c", "a", "b"), sortRefs(refs, RefSortState(RefSortKey.LastCommit, true), null, false).names())
        assertEquals(listOf("a", "b", "c"), sortRefs(refs, RefSortState(RefSortKey.LastCommit, false), null, false).names())
    }

    @Test
    fun `keeps the current branch on top for every sort`() {
        val head = "feature/CAPS-656-pallet-crop"

        for (key in RefSection.Local.sortKeys) {
            for (ascending in listOf(true, false)) {
                val sorted = sortRefs(branches, RefSortState(key, ascending), headName = head, keepHeadOnTop = true)

                assertEquals(head, sorted.first().name, "$key ascending=$ascending")
                assertEquals(branches.size, sorted.size)
            }
        }

        val notPinned = sortRefs(branches, RefSortState(), headName = head, keepHeadOnTop = false)

        assertEquals("bugfix/CAPS-700-hypermarket-reopen", notPinned.first().name)
    }

    @Test
    fun `shows age labels only for date sorts`() {
        val byName = buildRefRows(branches, RefSortState(), groupByPrefix = false, folderKeyPrefix = "local:", nowMillis = NOW)

        assertTrue(byName.filterIsInstance<RefRow.Item<String>>().all { it.ageLabel == null })

        val byDate = buildRefRows(
            branches,
            RefSortState(RefSortKey.LastCommit),
            groupByPrefix = false,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
        ).filterIsInstance<RefRow.Item<String>>()

        assertEquals(listOf("1d", "1w", "2w", "4w", "2mo", "6mo", "7mo"), byDate.map { it.ageLabel })
    }

    @Test
    fun `groups local branches by prefix with top-level branches after the folders`() {
        val rows = buildRefRows(
            branches,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            isFolderExpanded = allExpanded,
        )

        assertEquals(
            listOf(
                "[bugfix]",
                "  CAPS-700-hypermarket-reopen",
                "[feature]",
                "  CAPS-656-pallet-crop",
                "  CAPS-1034-migrate-ml-pipeline-hilt",
                "  CAPS-1249-5-free-archive-space",
                "[hotfix]",
                "  v3.0.4",
                "develop",
                "master",
            ),
            rows.labels(),
        )

        val feature = rows.filterIsInstance<RefRow.Folder>().single { it.label == "feature" }

        assertEquals("local:feature", feature.key)
        assertEquals(3, feature.count)
    }

    @Test
    fun `orders folders by their newest ref for date sorts and follows the order setting`() {
        val newest = buildRefRows(
            branches,
            RefSortState(RefSortKey.LastCommit, true),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            isFolderExpanded = { _, _ -> false },
        )

        assertEquals(listOf("[feature]", "[bugfix]", "[hotfix]", "develop", "master"), newest.labels())

        val oldest = buildRefRows(
            branches,
            RefSortState(RefSortKey.LastCommit, false),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            isFolderExpanded = { _, _ -> false },
        )

        assertEquals(listOf("[hotfix]", "[bugfix]", "[feature]", "master", "develop"), oldest.labels())

        val byNameDescending = buildRefRows(
            branches,
            RefSortState(RefSortKey.Name, false),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            isFolderExpanded = { _, _ -> false },
        )

        assertEquals(listOf("[hotfix]", "[feature]", "[bugfix]", "master", "develop"), byNameDescending.labels())
    }

    @Test
    fun `keeps the current branch on top with its full name, outside its folder`() {
        val rows = buildRefRows(
            branches,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            headName = "feature/CAPS-1249-5-free-archive-space",
            keepHeadOnTop = true,
            isFolderExpanded = allExpanded,
        )

        assertEquals("feature/CAPS-1249-5-free-archive-space", rows.labels().first())

        val feature = rows.filterIsInstance<RefRow.Folder>().single { it.label == "feature" }

        assertEquals(2, feature.count)
        assertEquals(1, rows.count { it.key == "refs/heads/feature/CAPS-1249-5-free-archive-space" })
    }

    @Test
    fun `keeps the current branch in its folder when not kept on top`() {
        val rows = buildRefRows(
            branches,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            headName = "feature/CAPS-1249-5-free-archive-space",
            keepHeadOnTop = false,
            isFolderExpanded = allExpanded,
        )

        assertEquals("[bugfix]", rows.labels().first())
        assertEquals(3, rows.filterIsInstance<RefRow.Folder>().single { it.label == "feature" }.count)
    }

    @Test
    fun `only the folder of the current branch is expanded by default`() {
        val rows = buildRefRows(
            branches,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "local:",
            nowMillis = NOW,
            headName = "feature/CAPS-1249-5-free-archive-space",
            keepHeadOnTop = true,
        )

        val expanded = rows.filterIsInstance<RefRow.Folder>().associate { it.label to it.isExpanded }

        assertEquals(mapOf("bugfix" to false, "feature" to true, "hotfix" to false), expanded)
        assertEquals(
            listOf(
                "feature/CAPS-1249-5-free-archive-space",
                "[bugfix]",
                "[feature]",
                "  CAPS-656-pallet-crop",
                "  CAPS-1034-migrate-ml-pipeline-hilt",
                "[hotfix]",
                "develop",
                "master",
            ),
            rows.labels(),
        )
    }

    @Test
    fun `groups remote branches under the remote name`() {
        // Inside the Remotes section, names come without the remote, and the remote is part of the folder key
        val remote = listOf(ref("develop", 8), ref("feature/x", 1), ref("feature/y", 3), ref("release/3.3.0", 15))

        val rows = buildRefRows(
            remote,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "remote:origin/",
            nowMillis = NOW,
            isFolderExpanded = allExpanded,
        )

        assertEquals(listOf("[feature]", "  x", "  y", "[release]", "  3.3.0", "develop"), rows.labels())
        assertEquals(
            listOf("remote:origin/feature", "remote:origin/release"),
            rows.filterIsInstance<RefRow.Folder>().map { it.key },
        )
    }

    @Test
    fun `groups tags by prefix and leaves version tags at the top level`() {
        val tags = listOf(ref("3.3.0"), ref("qa/2026-10-02"), ref("3.10.0"), ref("qa/2026-09-23"), ref("rc/3.4.0-rc1"))

        val rows = buildRefRows(
            tags,
            RefSortState(),
            groupByPrefix = true,
            folderKeyPrefix = "tags:",
            nowMillis = NOW,
            isFolderExpanded = allExpanded,
        )

        assertEquals(
            listOf("[qa]", "  2026-09-23", "  2026-10-02", "[rc]", "  3.4.0-rc1", "3.3.0", "3.10.0"),
            rows.labels(),
        )
        assertEquals("tags:qa", rows.first().key)
    }

    @Test
    fun `finds the folder of a ref name`() {
        assertEquals("feature", refFolderOf("feature/x"))
        assertEquals("feature", refFolderOf("feature/x/y"))
        assertNull(refFolderOf("develop"))
        assertNull(refFolderOf("3.3.0"))
    }

    @Test
    fun `folder expansion stores only states that differ from the default`() {
        var expansion = RefFolderExpansion()

        assertFalse(expansion.isExpanded("local:bugfix", default = false))
        assertTrue(expansion.isExpanded("local:feature", default = true))

        expansion = expansion.toggled("local:bugfix", default = false)

        assertTrue(expansion.isExpanded("local:bugfix", default = false))
        assertEquals(setOf("local:bugfix"), expansion.expanded)

        expansion = expansion.toggled("local:feature", default = true)

        assertFalse(expansion.isExpanded("local:feature", default = true))
        assertEquals(setOf("local:feature"), expansion.collapsed)

        expansion = expansion.toggled("local:bugfix", default = false)

        assertEquals(RefFolderExpansion(collapsed = setOf("local:feature")), expansion)
    }
}
