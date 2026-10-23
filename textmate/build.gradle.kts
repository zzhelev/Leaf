// A copy of kotlin-textmate's engine (MIT, see LICENSE and README.md), kept in Leaf so that Leaf can fix it.
plugins {
    id("buildsrc.convention.kotlin-jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.joni)
    implementation(libs.jcodings)
    // kotlin-textmate reads grammars with Gson, until the next commits move the reader to kotlinx.serialization.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
