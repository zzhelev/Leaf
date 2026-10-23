// A copy of kotlin-textmate's engine (MIT, see LICENSE and README.md), kept in Leaf so that Leaf can fix it.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    // For the tests' fixtures; the grammar reader walks the JSON tree itself.
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.joni)
    implementation(libs.jcodings)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
