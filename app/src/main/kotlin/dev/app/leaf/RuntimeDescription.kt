// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

/**
 * What Leaf runs on, in one line for the top of each run in `leaf.log`: Leaf's version, the system with its version and
 * CPU, and the Java runtime. [property] reads the JVM's system properties; a missing one reads as `?`.
 */
fun runtimeDescription(property: (String) -> String? = System::getProperty): String {
    fun read(key: String) = property(key)?.takeIf { it.isNotBlank() } ?: "?"

    return "${AppConstants.APP_NAME} ${AppConstants.APP_VERSION} on ${read("os.name")} ${read("os.version")} " +
        "(${read("os.arch")}), Java ${read("java.runtime.version")} from ${read("java.vendor")}"
}
