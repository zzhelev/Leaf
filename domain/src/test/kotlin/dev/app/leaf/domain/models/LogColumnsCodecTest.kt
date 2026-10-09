// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogColumnsCodecTest {
    @Test
    fun `round-trips the settings`() {
        val settings = LogColumnsSettings()
            .toggled(LogColumn.Author)
            .toggled(LogColumn.Date)
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
            listOf(LogColumn.Commit, LogColumn.Author, LogColumn.Date),
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
                LogColumnEntry(LogColumn.Commit, isVisible = true, width = 70f),
                LogColumnEntry(LogColumn.Author, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.Date, isVisible = true, width = 110f),
            ),
            settings.columns,
        )
    }

    @Test
    fun `adds missing columns at the end with their defaults`() {
        val settings = LogColumnsCodec.decode("""{"columns": [{"column": "Date", "visible": false}]}""")

        assertEquals(
            listOf(
                LogColumnEntry(LogColumn.Date, isVisible = false, width = 110f),
                LogColumnEntry(LogColumn.Author, isVisible = false, width = 150f),
                LogColumnEntry(LogColumn.Commit, isVisible = false, width = 80f),
            ),
            settings.columns,
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

        assertEquals(listOf(48f, 600f, 80f), settings.columns.map { it.width })
        assertEquals(56f, settings.graphMaxWidth)
    }

    @Test
    fun `the settings file stores columns by name`() {
        val text = LogColumnsCodec.encode(LogColumnsSettings())

        assertEquals(
            """{"columns":[{"column":"Author","visible":false,"width":150.0},""" +
                """{"column":"Date","visible":true,"width":110.0},""" +
                """{"column":"Commit","visible":false,"width":80.0}],""" +
                """"dateShowsTime":false,"graphMaxWidth":120.0}""",
            text,
        )
    }
}
