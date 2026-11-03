// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The tabs' names and colours, saved next to the tabs' paths and read back at the next start (upstream issue 242). */
class SavedTabLooksTest {
    private val acme = "/repos/acme/app/.git"
    private val acmeLinked = "/repos/acme/app/.git/worktrees/app-fix-login"
    private val globex = "/repos/globex/app/.git"

    @Test
    fun `each tab gets its own look back, two tabs of one worktree too`() {
        val tabs = listOf(
            acme to TabLook(name = "review", color = 5),
            acme to TabLook(),
            acmeLinked to TabLook(color = 1),
            globex to TabLook(name = "Globex app"),
        )

        val saved = savedTabLooksJson(tabs)

        assertEquals(tabs.map { it.second }, savedTabLooks(tabs.map { it.first }, saved))
    }

    @Test
    fun `tabs saved before the looks were have none`() {
        assertEquals(listOf(TabLook(), TabLook()), savedTabLooks(listOf(acme, globex), ""))
    }

    @Test
    fun `a tab list that an older Leaf saved again without the looks gives each tab its own or none`() {
        val saved = savedTabLooksJson(listOf(acme to TabLook(name = "review"), globex to TabLook(color = 2)))

        // The older Leaf moved Globex first, closed nothing, and opened dotfiles
        val paths = listOf(globex, acme, "/repos/dotfiles/.git")

        assertEquals(listOf(TabLook(), TabLook(), TabLook()), savedTabLooks(paths, saved))

        // It closed the second tab
        assertEquals(listOf(TabLook(name = "review")), savedTabLooks(listOf(acme), saved))
    }

    @Test
    fun `values that can't be read are none, and so is a list that isn't one`() {
        val unreadable = """[{"path":"$acme","name":"  \n ","color":9},{"path":"$globex","name":" Globex\tapp ","color":0}]"""

        assertEquals(listOf(TabLook(), TabLook(name = "Globex app")), savedTabLooks(listOf(acme, globex), unreadable))

        for (broken in listOf("{", "[1, 2]", "\"text\"", "[{\"name\":\"no path\"}]")) {
            assertEquals(listOf(TabLook()), savedTabLooks(listOf(acme), broken), broken)
        }

        val extraKeys = """[{"path":"$acme","name":"review","color":3,"pinned":true}]"""
        assertEquals(listOf(TabLook(name = "review", color = 3)), savedTabLooks(listOf(acme), extraKeys))
    }

    @Test
    fun `what is saved is a list of objects with the path`() {
        val saved = savedTabLooksJson(listOf(acme to TabLook(name = "review", color = 5), globex to TabLook()))

        assertEquals(
            Json.parseToJsonElement("""[{"path":"$acme","name":"review","color":5},{"path":"$globex"}]"""),
            Json.parseToJsonElement(saved),
        )
    }
}
