// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The commit that a `fixup!`, `squash!` or `amend!` subject names, and "Fixup into parent", which is refused when that
 * is another commit than the parent (fork-only). `AutosquashTargetTest` (data) checks the names against git's own
 * `--autosquash`.
 */
class FixupTargetTest {
    private val someone = Identity("Author", "a@example.invalid")

    private fun commit(hash: Char, message: String) =
        Commit(hash.toString().repeat(40), message, someone, someone, 0, emptyList())

    private val parser = commit('1', "Add the parser\n\nReads the files.\n")
    private val cache = commit('2', "Cache the parsed files\n")
    private val tests = commit('3', "Test the cache\n")

    private fun target(message: String, vararg older: Commit) = autosquashTarget(commit('f', message), older.toList())

    @Test
    fun `a subject names the oldest commit with that subject, then a hash, then a commit whose subject it starts`() {
        val again = commit('4', "Add the parser\n")

        assertEquals(parser, target("fixup! Add the parser\n", parser, cache, again))
        assertEquals(cache, target("fixup! 2222222\n", parser, cache))
        assertEquals(cache, target("fixup! 2222222222222222222222222222222222222222\n", parser, cache))
        assertEquals(parser, target("fixup! Add the\n", parser, cache))
        // The whole subject wins over one that only starts with it
        val add = commit('5', "Add\n")
        assertEquals(add, target("fixup! Add\n", parser, add))
    }

    @Test
    fun `squash, amend and repeated prefixes name the same way, and a subject without one names nothing`() {
        assertEquals(cache, target("squash! Cache the parsed files\n", parser, cache))
        assertEquals(cache, target("amend! Cache the parsed files\n\nBetter words.\n", parser, cache))
        assertEquals(cache, target("fixup! fixup!  squash! Cache the parsed files\n", parser, cache))
        assertNull(target("Cache the parsed files\n", parser, cache))
        assertNull(target("fixup!Cache the parsed files\n", parser, cache))
        assertNull(target("Fixup! Cache the parsed files\n", parser, cache))
    }

    @Test
    fun `a subject is the first paragraph, as git's subject joins its lines`() {
        assertEquals(cache, target("fixup! Cache the\nparsed files\n\nBody\n", parser, cache))
        // Its first line alone would name the oldest commit that starts with it
        val addCache = commit('6', "Add the cache\n")
        assertEquals(parser, target("fixup! Add the\nparser\n", addCache, parser))
        // And a named commit's subject is its first paragraph too
        val empty = commit('7', "Handle the\nempty file\n")
        assertEquals(empty, target("fixup! Handle the empty file\n", parser, empty))
        assertEquals(parser, target("fixup! Add the parser\r\n", parser))
        assertNull(target("fixup! Cache the parsed files\n", parser))
    }

    @Test
    fun `a hash names only from four digits on, and a commit the line doesn't have names nothing`() {
        assertNull(target("fixup! 222\n", parser, cache))
        assertNull(target("fixup! 9999999\n", parser, cache))
        assertNull(target("fixup! Bump the version\n", parser, cache))
    }

    // feat, newest first: fixes (f), tests, cache, parser
    private fun line(vararg newestFirst: Commit) = BranchLine("refs/heads/feat", "refs/heads/main", newestFirst.toList())

    private fun Either<LineRange, RewriteRefusal>.hashes() = (this as Either.Ok).value.commits.map { it.hash.first() }

    @Test
    fun `a fixup that names its parent, or nothing on the line, joins it with its parent`() {
        val named = commit('f', "fixup! Test the cache\n")
        val nothing = commit('f', "fixup! Bump the version\n")
        val plain = commit('f', "Fix a typo\n")

        assertEquals(listOf('3', 'f'), line(named, tests, cache, parser).commitToFixUp(named.hash).hashes())
        assertEquals(listOf('3', 'f'), line(nothing, tests, cache, parser).commitToFixUp(nothing.hash).hashes())
        assertEquals(listOf('3', 'f'), line(plain, tests, cache, parser).commitToFixUp(plain.hash).hashes())
    }

    @Test
    fun `a fixup that names an older commit is refused with it, and squashing into the parent isn't`() {
        val fix = commit('f', "fixup! Add the parser\n")
        val branch = line(fix, tests, cache, parser)

        assertEquals(Either.Err(RewriteRefusal.NamesAnotherCommit(parser)), branch.commitToFixUp(fix.hash))
        assertEquals(listOf('3', 'f'), branch.commitWithParent(fix.hash).hashes())

        val choices = BranchLines(lines = mapOf(branch.branch to branch)).commitChoices(fix.hash, null) { null }
        assertEquals(Either.Err(RewriteRefusal.NamesAnotherCommit(parser)), choices.fixup)
        assertEquals(listOf('3', 'f'), choices.intoParent.hashes())
    }

    @Test
    fun `a parent with the subject named counts as named, though an older commit has it too`() {
        val again = commit('4', "Add the parser\n")
        val fix = commit('f', "fixup! Add the parser\n")

        assertEquals(listOf('4', 'f'), line(fix, again, cache, parser).commitToFixUp(fix.hash).hashes())
    }

    @Test
    fun `a parent that the subject only starts, where an older commit has the whole subject, isn't named`() {
        val add = commit('5', "Add\n")
        val fix = commit('f', "fixup! Add\n")

        assertEquals(Either.Err(RewriteRefusal.NamesAnotherCommit(add)), line(fix, parser, add).commitToFixUp(fix.hash))
    }

    @Test
    fun `the first commit's refusal comes before what its subject names`() {
        val fix = commit('f', "fixup! Add the parser\n")

        assertEquals(Either.Err(RewriteRefusal.FirstCommit), line(fix).commitToFixUp(fix.hash))
    }
}
