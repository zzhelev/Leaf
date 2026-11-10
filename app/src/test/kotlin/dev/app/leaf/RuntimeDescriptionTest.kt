// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeDescriptionTest {
    @Test
    fun `names Leaf's version, the system, its CPU and the Java runtime`() {
        val properties = mapOf(
            "os.name" to "Mac OS X",
            "os.version" to "26.6",
            "os.arch" to "aarch64",
            "java.runtime.version" to "25.0.4+1-b329.73",
            "java.vendor" to "JetBrains s.r.o.",
        )

        assertEquals(
            "Leaf ${AppConstants.APP_VERSION} on Mac OS X 26.6 (aarch64), Java 25.0.4+1-b329.73 from JetBrains s.r.o.",
            runtimeDescription(properties::get),
        )
    }

    @Test
    fun `a property that the JVM lacks reads as a question mark`() {
        val line = runtimeDescription { key -> if (key == "os.name") "Linux" else null }

        assertTrue(line.startsWith("Leaf ${AppConstants.APP_VERSION} on Linux ? (?), Java ? from ?"), line)
    }

    @Test
    fun `this JVM's own properties are all there`() {
        assertTrue("?" !in runtimeDescription(), runtimeDescription())
    }
}
