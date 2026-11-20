// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogColumnsCodecTest {
    @Test
    fun `round-trips the settings`() {
        val settings = LogColumnsSettings()
            .toggled(LogColumn.Author)
            .toggled(LogColumn.CommitDate)
            .resized(LogColumn.Commit, 96f)
            .withDateShowingTime(true)
            .withGraphMaxWidth(240f)

        assertEquals(settings, LogColumnsCodec.decode(LogColumnsCodec.encode(settings)))
    }

    @Test
    fun `keeps the stored order`() {
        val text = """
            {"columns": [
                {"column": "Commit", "visible": true, "width": 80},
                {"column": "Author", "visible": true, "width": 150},
                {"column": "Date", "visible": true, "width": 110}
            ]}
        """

        assertEquals(
            listOf(LogColumn.Commit, LogColumn.Author, LogColumn.AuthorDate, LogColumn.Committer, LogColumn.CommitDate),
            LogColumnsCodec.decode(text).columns.map { it.column },
        )
    }

    @Test
    fun `missing or unreadable text gives the defaults`() {
        assertEquals(LogColumnsSettings(), LogColumnsCodec.decode(null))
        assertEquals(LogColumnsSettings(), LogColumnsCodec.decode(""))
        assertEquals(LogColumnsSettings(), LogColumnsCodec.decode("not json"))
        assertEquals(LogColumnsSettings(), LogColumnsCodec.decode("[1, 2]"))
        assertEquals(LogColumnsSettings(), LogColumnsCodec.decode("{}"))
    }

    @Test
    fun `skips unknown columns and fields, and keeps the first entry of a column listed twice`() {
        val text = """
            {"columns": [
                {"column": "Signature", "visible": true, "width": 90},
                {"column": "Commit", "visible": true, "width": 70, "align": "end"},
                {"column": "Commit", "visible": false, "width": 200}
            ], "density": 3}
        """

        val settings = LogColumnsCodec.decode(text)

        assertEquals(
            listOf(
                LogColumnEntry(LogColumn.Author, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.AuthorDate, isVisible = false, width = 110f),
                LogColumnEntry(LogColumn.Committer, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.CommitDate, isVisible = true, width = 110f),
                LogColumnEntry(LogColumn.Commit, isVisible = true, width = 70f),
            ),
            settings.columns,
        )
    }

    @Test
    fun `adds missing columns with their defaults where they'd be by default`() {
        val settings = LogColumnsCodec.decode("""{"columns": [{"column": "Date", "visible": false}]}""")

        assertEquals(
            listOf(
                LogColumnEntry(LogColumn.Author, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.AuthorDate, isVisible = false, width = 110f),
                LogColumnEntry(LogColumn.Committer, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.CommitDate, isVisible = false, width = 110f),
                LogColumnEntry(LogColumn.Commit, isVisible = false, width = 80f),
            ),
            settings.columns,
        )
    }

    @Test
    fun `settings saved before the committer column existed get it after the author, hidden`() {
        val text = """
            {"columns": [
                {"column": "Author", "visible": true, "width": 170},
                {"column": "Date", "visible": true, "width": 180},
                {"column": "Commit", "visible": true, "width": 80}
            ], "dateShowsTime": true, "graphMaxWidth": 200}
        """

        assertEquals(
            LogColumnsSettings(
                columns = listOf(
                    LogColumnEntry(LogColumn.Author, isVisible = true, width = 170f),
                    LogColumnEntry(LogColumn.AuthorDate, isVisible = false, width = 110f),
                    LogColumnEntry(LogColumn.Committer, isVisible = false, width = 150f),
                    LogColumnEntry(LogColumn.CommitDate, isVisible = true, width = 180f),
                    LogColumnEntry(LogColumn.Commit, isVisible = true, width = 80f),
                ),
                dateShowsTime = true,
                graphMaxWidth = 200f,
            ),
            LogColumnsCodec.decode(text),
        )
    }

    @Test
    fun `settings saved before the author date column existed keep their commit date and get it hidden after the author`() {
        val text = """
            {"columns": [
                {"column": "Commit", "visible": true, "width": 80},
                {"column": "Author", "visible": true, "width": 170},
                {"column": "Committer", "visible": true, "width": 150},
                {"column": "Date", "visible": true, "width": 180}
            ], "dateShowsTime": true}
        """

        val settings = LogColumnsCodec.decode(text)

        assertEquals(
            listOf(
                LogColumnEntry(LogColumn.Commit, isVisible = true, width = 80f),
                LogColumnEntry(LogColumn.Author, isVisible = true, width = 170f),
                LogColumnEntry(LogColumn.AuthorDate, isVisible = false, width = 110f),
                LogColumnEntry(LogColumn.Committer, isVisible = true, width = 150f),
                LogColumnEntry(LogColumn.CommitDate, isVisible = true, width = 180f),
            ),
            settings.columns,
        )
        assertTrue(settings.dateShowsTime)
    }

    @Test
    fun `the commit date is stored as Date, and a column named CommitDate isn't one`() {
        val text = LogColumnsCodec.encode(LogColumnsSettings().toggled(LogColumn.AuthorDate))

        assertTrue(""""column":"Date","visible":true""" in text)
        assertTrue(""""column":"AuthorDate","visible":true""" in text)
        assertFalse(""""column":"CommitDate"""" in text)
        assertEquals(
            LogColumnsSettings(),
            LogColumnsCodec.decode("""{"columns": [{"column": "CommitDate", "visible": false}]}"""),
        )
    }

    @Test
    fun `out-of-range or missing widths are fixed`() {
        val text = """
            {"columns": [
                {"column": "Author", "visible": true, "width": 4},
                {"column": "Date", "visible": true, "width": 99999},
                {"column": "Commit", "visible": true, "width": null}
            ], "graphMaxWidth": 3}
        """

        val settings = LogColumnsCodec.decode(text)

        assertEquals(listOf(48f, 110f, 150f, 600f, 80f), settings.columns.map { it.width })
        assertEquals(56f, settings.graphMaxWidth)
    }

    @Test
    fun `the settings file stores columns by name`() {
        val text = LogColumnsCodec.encode(LogColumnsSettings())

        assertEquals(
            """{"columns":[{"column":"Author","visible":false,"width":150.0},""" +
                """{"column":"AuthorDate","visible":false,"width":110.0},""" +
                """{"column":"Committer","visible":false,"width":150.0},""" +
                """{"column":"Date","visible":true,"width":110.0},""" +
                """{"column":"Commit","visible":false,"width":80.0}],""" +
                """"dateShowsTime":false,"graphMaxWidth":120.0}""",
            text,
        )
    }
}
