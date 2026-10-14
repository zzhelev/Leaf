// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.AgentMarker
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBaseBranch
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeLabelRule
import dev.app.leaf.domain.models.WorktreeLabelRulesCodec
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

private const val HOME = "/Users/ann"
private const val REPO = "/Users/ann/dev/shop"

class WorktreeOriginsTest {
    private fun matches(pattern: String, path: String, repo: String? = REPO) =
        folderPatternRegex(pattern, HOME, repo)?.matches(path) == true

    @Test
    fun `a star stands for one folder name or part of one, two stars for any number of folders`() {
        assertTrue(matches("<repo>/.claude/worktrees/*", "$REPO/.claude/worktrees/busy-jang"))
        assertFalse(matches("<repo>/.claude/worktrees/*", "$REPO/.claude/worktrees/a/b"))
        assertFalse(matches("<repo>/.claude/worktrees/*", "$REPO/.claude/worktrees"))
        assertTrue(matches("<repo>-junie-wt-*", "$REPO-junie-wt-01"))
        assertTrue(matches("~/agents/**/wt", "$HOME/agents/wt"))
        assertTrue(matches("~/agents/**/wt", "$HOME/agents/a/b/wt"))
        assertTrue(matches("~/agents/**", "$HOME/agents/a/b"))
        assertTrue(matches("~/agent-?", "$HOME/agent-1"))
        assertFalse(matches("~/agent-?", "$HOME/agent-12"))
    }

    @Test
    fun `the placeholders stand for the home folder, the repository and its name`() {
        assertTrue(matches("~/.codex/worktrees/*/*", "$HOME/.codex/worktrees/a1b2/shop"))
        assertFalse(matches("~/.codex/worktrees/*/*", "/Users/bob/.codex/worktrees/a1b2/shop"))
        assertTrue(matches("<repo>.worktrees/*", "$REPO.worktrees/fix-login"))
        assertTrue(matches("~/wt/<repo-name>/*", "$HOME/wt/shop/fix"))
        assertFalse(matches("~/wt/<repo-name>/*", "$HOME/wt/other/fix"))
        // Without a main worktree, a rule that names it matches nothing
        assertFalse(matches("<repo>/.claude/worktrees/*", "$REPO/.claude/worktrees/x", repo = null))
    }

    @Test
    fun `a pattern without a start is in the repository, and everything else matches as it is`() {
        assertTrue(matches(".agents/*", "$REPO/.agents/one"))
        assertFalse(matches(".agents/*", "$HOME/.agents/one"))
        // Two stars at the start match anywhere, next to the repository too
        assertTrue(matches("**/aider-*", "/private/tmp/demo/aider-docs"))
        assertTrue(matches("**/aider-*", "$REPO/sub/aider-x"))
        assertFalse(matches("**/aider-*", "/private/tmp/demo/aider-docs/inner"))
        assertTrue(matches("/tmp/wt/*", "/tmp/wt/one"))
        assertTrue(matches("/tmp/wt/*/", "/tmp/wt/one"))
        // A dot is a dot, and a repository's brackets are its folder's
        assertFalse(matches("<repo>.worktrees/*", "/Users/ann/dev/shopXworktrees/x"))
        val bracketed = "/Users/ann/dev/a[b]+(c)"
        assertTrue(matches("<repo>/.claude/worktrees/*", "$bracketed/.claude/worktrees/x", repo = bracketed))
        // Case is ignored, and Windows' separators are separators
        assertTrue(matches("~/.Cursor/Worktrees/*/*", "$HOME/.cursor/worktrees/shop/abc"))
        assertTrue(
            folderPatternRegex("""~\.cline\worktrees\*\*""", """C:\Users\ann""", null)!!
                .matches("C:/Users/ann/.cline/worktrees/1a2b3/shop")
        )
        assertNull(folderPatternRegex("  ", HOME, REPO))
    }

    @Test
    fun `the user's rules come first, then markers, then the tools' folders`() {
        val claude = worktree("$REPO/.claude/worktrees/busy-jang")
        val codex = worktree("$HOME/somewhere/shop", marker = AgentMarker.CODEX)
        val cursor = worktree("$HOME/.cursor/worktrees/shop/ZMR40")
        val markedInFolder = worktree("$REPO/.claude/worktrees/desk", marker = AgentMarker.CLAUDE_DESKTOP)
        val mine = worktree("$HOME/agents/shop-review")
        val unknown = worktree("$HOME/dev/shop-feature")
        val rules = listOf(
            WorktreeLabelRule("Aider", "~/agents/*"),
            WorktreeLabelRule("", "~/dev/*"),
            WorktreeLabelRule("Blank", " "),
        )

        val origins = list(claude, codex, cursor, markedInFolder, mine, unknown).withOrigins(rules, HOME) { it }
            .worktrees.map { it.origin }

        assertEquals(
            listOf(
                null,
                WorktreeOrigin.InToolFolder(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*"),
                WorktreeOrigin.Marked(AgentMarker.CODEX),
                WorktreeOrigin.InToolFolder(AgentTool.CURSOR, "~/.cursor/worktrees/*/*"),
                WorktreeOrigin.Marked(AgentMarker.CLAUDE_DESKTOP),
                WorktreeOrigin.Labelled(WorktreeLabelRule("Aider", "~/agents/*")),
                null,
            ),
            origins,
        )
        assertEquals(listOf(null, "Claude", "Codex", "Cursor", "Claude", "Aider", null), origins.map { it?.label })

        // The main worktree never gets one, even when a rule matches its folder
        assertNull(list().withOrigins(listOf(WorktreeLabelRule("All", "/**")), HOME) { it }.worktrees[0].origin)

        // A rule of the user's wins over a marker
        val relabelled = list(codex).withOrigins(listOf(WorktreeLabelRule("Mine", "~/somewhere/*")), HOME) { it }

        assertEquals("Mine", relabelled.worktrees[1].origin?.label)
    }

    @Test
    fun `folders match as git gives them or as real paths`() {
        // macOS gives temporary folders as /var/… and their real paths as /private/var/…
        val realPath = { path: String -> if (path.startsWith("/var/")) "/private$path" else path }
        val main = worktree("/private/var/folders/x/shop", isMain = true)
        val agent = worktree("/var/folders/x/shop/.claude/worktrees/a")

        val list = WorktreeList(WorktreeBaseBranch("refs/heads/main"), listOf(info(main), info(agent)))

        assertEquals("Claude", list.withOrigins(emptyList(), HOME, realPath).worktrees[1].origin?.label)
        assertNull(list.withOrigins(emptyList(), HOME) { it }.worktrees[1].origin)
    }

    @Test
    fun `filters worktrees by their tag`() {
        // Neither folder says "codex"
        val list = list(worktree("$HOME/somewhere/shop", marker = AgentMarker.CODEX), worktree("$HOME/dev/other"))
            .withOrigins(emptyList(), HOME) { it }

        assertEquals(listOf("shop"), worktreeRows(list, filter = "CODEX", nowMillis = 0).map { it.name })
    }

    @Test
    fun `reads the lock reason that Claude Code writes`() {
        assertEquals(
            ClaudeSessionLockReason("probe-wt", 48472, Instant.parse("2026-10-14T07:17:40Z")),
            parseClaudeSessionLock("claude session probe-wt (pid 48472 start Wed Oct 14 07:17:40 2026)"),
        )
        // ps pads a day below 10 with a space
        assertEquals(
            Instant.parse("2026-10-04T23:05:09Z"),
            parseClaudeSessionLock("claude session a b (pid 7 start Sun Oct  4 23:05:09 2026)")?.start,
        )
        assertEquals("a b", parseClaudeSessionLock("claude session a b (pid 7 start ?)")?.session)
        assertNull(parseClaudeSessionLock("claude session a b (pid 7 start ?)")?.start)
        assertNull(parseClaudeSessionLock("agent running"))
        assertNull(parseClaudeSessionLock("claude session x (pid y start z)"))
    }

    @Test
    fun `stores the user's rules in their order, rules being typed included`() {
        val rules = listOf(WorktreeLabelRule("Aider", "~/aider/*"), WorktreeLabelRule("", ""))

        assertEquals(rules, WorktreeLabelRulesCodec.decode(WorktreeLabelRulesCodec.encode(rules)))
        assertEquals(emptyList<WorktreeLabelRule>(), WorktreeLabelRulesCodec.decode("not json"))
        assertEquals(emptyList<WorktreeLabelRule>(), WorktreeLabelRulesCodec.decode(null))
        assertEquals(
            listOf(WorktreeLabelRule("A", "")),
            WorktreeLabelRulesCodec.decode("""[{"label":"A","other":1}]"""),
        )
    }

    private fun list(vararg linked: Worktree) = WorktreeList(
        WorktreeBaseBranch("refs/heads/main"),
        listOf(info(worktree(REPO, isMain = true))) + linked.map(::info),
    )

    private fun worktree(path: String, isMain: Boolean = false, marker: AgentMarker? = null) = Worktree(
        path = path,
        headSha = "0123456789abcdef0123456789abcdef01234567",
        branch = null,
        isMain = isMain,
        isCurrent = false,
        isDetached = true,
        isBare = false,
        locked = null,
        prunable = null,
        agentMarker = marker,
    )

    private fun info(worktree: Worktree) =
        WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null)
}
