// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

// Fork-only. What Leaf knows about a release that it can update from: its manifest, the keys that may sign it, and the
// check of their signatures (docs/fork/auto-update.md). sign-manifest.sh, next to this file, writes and signs the
// manifest in the release workflow; the tests run it.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
}
