// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.ignore

import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the ignore dialog offers, and the rules it writes. `AddIgnoreRulesGitActionTest` checks them against git. */
class IgnoreRulesTest {
    @Test
    fun `a file is ignored by its anchored path, its folder by a folder rule and its extension by a wildcard`() {
        assertEquals("/build/out.log", ignoreFileRule("build/out.log"))
        assertEquals("/out.log", ignoreFileRule("out.log"))
        assertEquals("/build/", ignoreFolderRule("build"))
        assertEquals("/a/b/", ignoreFolderRule("a/b"))
        assertEquals("*.log", ignoreExtensionRule("log"))
    }

    @Test
    fun `characters that a pattern reads as more than themselves are escaped`() {
        assertEquals("/st\\*r\\?\\[ac]", ignoreFileRule("st*r?[ac]"))
        assertEquals("/a\\\\b", ignoreFileRule("a\\b"))
        assertEquals("/dir\\[x]/", ignoreFolderRule("dir[x]"))
        assertEquals("*.a\\*b", ignoreExtensionRule("a*b"))
    }

    @Test
    fun `trailing spaces are escaped, as git drops them from a line otherwise`() {
        assertEquals("/trail\\ ", ignoreFileRule("trail "))
        assertEquals("/trail\\ \\ \\ ", ignoreFileRule("trail   "))
        assertEquals("/a b/c d", ignoreFileRule("a b/c d"))
        assertEquals("/a\\ /", ignoreFolderRule("a "))
        assertEquals("/a\\ /b", ignoreFileRule("a /b"))
        assertEquals("*.x\\ ", ignoreExtensionRule("x "))
    }

    @Test
    fun `white space other than spaces that ends a name is escaped too, as JGit drops it otherwise`() {
        assertEquals("/tab\\\t", ignoreFileRule("tab\t"))
        assertEquals("/a\\\u2003/", ignoreFolderRule("a\u2003"))
        assertEquals("/a b/c\\ \\\t", ignoreFileRule("a b/c \t"))
    }

    @Test
    fun `a leading hash or bang can't start a rule, as every rule starts with a slash or a star`() {
        assertEquals("/#hash", ignoreFileRule("#hash"))
        assertEquals("/!bang", ignoreFileRule("!bang"))
        assertEquals("*.#h", ignoreExtensionRule("#h"))
    }

    @Test
    fun `a folder with a line break in its name has no rule`() {
        assertNull(ignoreFolderRule("a\nb"))
        assertNull(ignoreFolderRule("a\rb"))
    }

    @Test
    fun `the extension is what follows the last dot of the name, unless the dot starts or ends it`() {
        assertEquals("log", extensionOf("build/out.log"))
        assertEquals("gz", extensionOf("a.tar.gz"))
        assertEquals("local", extensionOf(".env.local"))
        assertEquals("md", extensionOf("a.b/README.md"))
        assertNull(extensionOf(".env"))
        assertNull(extensionOf("Makefile"))
        assertNull(extensionOf("a.b/Makefile"))
        assertNull(extensionOf("name."))
    }

    @Test
    fun `the folder is the path without its name, and a file at the root has none`() {
        assertEquals("a/b", folderOf("a/b/c.txt"))
        assertNull(folderOf("c.txt"))
    }

    @Test
    fun `one file gets its file, its folder and its extension`() {
        val options = ignoreOptions(IgnoreRequest.Files(listOf("build/out.log")))

        assertEquals(
            listOf(
                IgnoreOption(IgnoreKind.FILES, listOf("build/out.log"), listOf("/build/out.log")),
                IgnoreOption(IgnoreKind.FOLDERS, listOf("build/"), listOf("/build/")),
                IgnoreOption(IgnoreKind.EXTENSIONS, listOf("*.log"), listOf("*.log")),
            ),
            options,
        )
    }

    @Test
    fun `a file at the root has no folder, and one without an extension has no extension option`() {
        assertEquals(listOf(IgnoreKind.FILES), ignoreOptions(IgnoreRequest.Files(listOf("Makefile"))).map { it.kind })
        assertEquals(
            listOf(IgnoreKind.FILES, IgnoreKind.EXTENSIONS),
            ignoreOptions(IgnoreRequest.Files(listOf("out.log"))).map { it.kind },
        )
    }

    @Test
    fun `several files give each folder and each extension once, and a folder inside another one is left out`() {
        val options = ignoreOptions(
            IgnoreRequest.Files(listOf("a/b/x.log", "a/y.log", "a/b/z.tmp", "c/w.log", "v.log", "v.log")),
        ).associateBy { it.kind }

        assertEquals(
            listOf("a/b/x.log", "a/y.log", "a/b/z.tmp", "c/w.log", "v.log"),
            options.getValue(IgnoreKind.FILES).names,
        )
        assertEquals(listOf("a/", "c/"), options.getValue(IgnoreKind.FOLDERS).names)
        assertEquals(listOf("/a/", "/c/"), options.getValue(IgnoreKind.FOLDERS).rules)
        assertEquals(listOf("*.log", "*.tmp"), options.getValue(IgnoreKind.EXTENSIONS).rules)
    }

    @Test
    fun `a file with a line break in its name is left out and counted`() {
        val options = ignoreOptions(IgnoreRequest.Files(listOf("ok.txt", "bad\nname.txt")))

        assertEquals(listOf("/ok.txt"), options.first().rules)
        assertEquals(1, options.first().unsupported)
        assertEquals(emptyList<IgnoreOption>(), ignoreOptions(IgnoreRequest.Files(listOf("bad\nname.txt"))))
    }

    @Test
    fun `a folder row gives its folder`() {
        assertEquals(
            listOf(IgnoreOption(IgnoreKind.FOLDERS, listOf("build/gen/"), listOf("/build/gen/"))),
            ignoreOptions(IgnoreRequest.Folder("build/gen")),
        )
    }

    @Test
    fun `a custom pattern is one rule per line, as typed, without blank lines`() {
        assertEquals(listOf("*.tmp", "/x/", "  lead"), customRules("*.tmp\n\n   \n/x/\r\n  lead"))
        assertEquals(emptyList<String>(), customRules("  \n\n"))
    }

    @Test
    fun `a menu takes the untracked files of the selection, and nothing in Staged`() {
        val entries = listOf(
            StatusEntry("new.txt", StatusType.ADDED),
            StatusEntry("README.md", StatusType.MODIFIED),
            StatusEntry("gone.txt", StatusType.REMOVED),
            StatusEntry("both.txt", StatusType.CONFLICTING),
            StatusEntry("dir/other.log", StatusType.ADDED),
        )

        assertEquals(
            IgnoreRequest.Files(listOf("new.txt", "dir/other.log")),
            ignoreRequestFor(entries, EntryType.UNSTAGED),
        )
        assertNull(ignoreRequestFor(entries, EntryType.STAGED))
        assertNull(ignoreRequestFor(entries.filter { it.statusType != StatusType.ADDED }, EntryType.UNSTAGED))
        assertNull(ignoreRequestFor(emptyList(), EntryType.UNSTAGED))
    }

    @Test
    fun `a folder row offers its folder only in Unstaged and only when it shows an untracked file`() {
        val withNew = listOf(StatusEntry("a/b/x", StatusType.MODIFIED), StatusEntry("a/b/y", StatusType.ADDED))
        val onlyTracked = listOf(StatusEntry("a/b/x", StatusType.MODIFIED), StatusEntry("a/b/z", StatusType.REMOVED))

        assertEquals(IgnoreRequest.Folder("a/b"), ignoreRequestForFolder(withNew, EntryType.UNSTAGED, "a/b"))
        assertNull(ignoreRequestForFolder(onlyTracked, EntryType.UNSTAGED, "a/b"))
        assertNull(ignoreRequestForFolder(withNew, EntryType.STAGED, "a/b"))
        assertNull(ignoreRequestForFolder(emptyList(), EntryType.UNSTAGED, "a/b"))
    }

    @Test
    fun `the rules to add are those the file doesn't have, each once`() {
        assertEquals(listOf("/b", "/c"), rulesToAdd(listOf("/a", "# /b", "/a"), listOf("/a", "/b", "/c", "/b")))
        assertEquals(emptyList<String>(), rulesToAdd(listOf("/a"), listOf("/a")))
    }
}
