import org.gradle.jvm.tasks.Jar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

plugins {    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    //application
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

val javaLanguageVersion = JavaLanguageVersion.of(25)
val linuxArmTarget = "aarch64-unknown-linux-gnu"
val linuxX64Target = "x86_64-unknown-linux-gnu"

// Remember to update Constants.APP_VERSION when changing this version
val projectVersion = "1.2.0"

val projectName = "Leaf"

// Required for JPackage, as it doesn't accept additional suffixes after the version.
val projectVersionSimplified = "1.2.0"

val appPackageName = "dev.app.leaf"
group = appPackageName
version = projectVersion

val isLinuxAarch64 = (properties.getOrDefault("isLinuxAarch64", "false") as String).toBoolean()
val useCross = (properties.getOrDefault("useCross", "false") as String).toBoolean()
val isRustRelease = (properties.getOrDefault("isRustRelease", "true") as String).toBoolean()


sourceSets.main.get().java.srcDirs("app/src/main/resources").includes.addAll(arrayOf("**/*.*"))

dependencies {
    implementation(project(":common"))
    implementation(project(":data"))
    implementation(project(":domain"))

    val composeDependency = when {
        currentOs() == OS.LINUX && isLinuxAarch64 -> libs.compose.desktop.linux.arm64
        else -> compose.desktop.currentOs
    }

    println("composeDependency: $composeDependency")
    implementation(composeDependency)

    implementation(libs.compose.ui.util)
    implementation(libs.compose.components.animatedimage)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation3)

    implementation(libs.jgit.core)
    implementation(libs.jgit.lfs)

    implementation(libs.coroutines)
    implementation(libs.kotlinx.coroutines.jvm)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.mockk)

    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.reload4j)

    implementation(libs.ktor.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)

    implementation(libs.datastore)
    implementation(libs.datastore.preferences)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

fun currentOs(): OS {
    val os = System.getProperty("os.name")
    return when {
        os.equals("Mac OS X", ignoreCase = true) -> OS.MAC
        os.startsWith("Win", ignoreCase = true) -> OS.WINDOWS
        os.startsWith("Linux", ignoreCase = true) -> OS.LINUX
        else -> error("Unknown OS name: $os")
    }
}

enum class OS {
    LINUX,
    WINDOWS,
    MAC
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(javaLanguageVersion)
    }
}

tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java) {
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

compose.desktop {
    application {
        mainClass = "dev.app.leaf.MainKt"

        // Skiko, Compose's renderer, loads its native library: JDK 25 warns without this, and a later JDK will refuse.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            includeAllModules = true
            packageName = projectName
            version = projectVersionSimplified
            description = "Multiplatform Git client"
            // The .deb's Maintainer field is "<vendor> <debMaintainer>".
            vendor = "Zhelyazko Zhelev"
            // Each format builds only on its own OS. Elsewhere its task is skipped, e.g. packageDeb on macOS.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)

            windows {
                iconFile.set(project.file("../icons/icon.ico"))
            }

            linux {
                iconFile.set(project.file("../icons/icon.png"))
                // Installs to /opt/leaf, with a launcher in the desktop's application menu.
                packageName = "leaf"
                debMaintainer = "zzhelev@gmail.com"
                appCategory = "vcs"
                menuGroup = "Development;RevisionControl;"
                shortcut = true
            }

            macOS {
                jvmArgs(
                    "-Dapple.awt.application.appearance=system"
                )
                iconFile.set(project.file("../icons/icon.icns"))
                bundleID = "io.github.zzhelev.leaf"
                signing {
                    sign.set(providers.gradleProperty("compose.desktop.mac.sign").map(String::toBoolean).orElse(true))
                    identity.set(providers.environmentVariable("SIGNING_IDENTITY"))
                }
                notarization {
                    val providers = project.providers
                    appleID.set(providers.environmentVariable("NOTARIZATION_APPLE_ID"))
                    password.set(providers.environmentVariable("NOTARIZATION_PASSWORD"))
                    teamID.set(providers.environmentVariable("NOTARIZATION_TEAM_ID"))
                }
            }
        }
    }
}

// Builds Leaf.app and installs it into /Applications, replacing an existing copy. macOS only.
// -PinstallDir=<dir> installs somewhere else, for example $HOME/Applications.
if (currentOs() == OS.MAC) {
    tasks.register("installMacApp") {
        group = "compose desktop"
        description = "Builds $projectName.app and installs it into /Applications, replacing an existing copy."
        dependsOn("createDistributable")

        val builtApp = layout.buildDirectory.dir("compose/binaries/main/app/$projectName.app")
        val installDir = providers.gradleProperty("installDir").orElse("/Applications")
        val bundleId = checkNotNull(compose.desktop.application.nativeDistributions.macOS.bundleID)

        doLast {
            val target = File(installDir.get(), "$projectName.app")
            // Matches processes whose command line starts with the app's executable, so a shell or editor that merely
            // mentions the path doesn't count. A Leaf that was quit a moment ago can take a few seconds to exit, so
            // wait up to 5 s before giving up.
            val escapedPath = "${target.absolutePath}/Contents/MacOS/".replace(Regex("""[.^$|?*+()\[\]{}\\]""")) {
                "\\" + it.value
            }
            val executablePattern = "^$escapedPath"
            fun runningProcesses(): String {
                val pgrep = ProcessBuilder("pgrep", "-fl", executablePattern).start()
                val output = pgrep.inputStream.bufferedReader().readText().trim()
                return if (pgrep.waitFor() == 0) output else ""
            }
            var running = runningProcesses()
            repeat(5) {
                if (running.isNotEmpty()) {
                    Thread.sleep(1000)
                    running = runningProcesses()
                }
            }
            check(running.isEmpty()) {
                "$projectName is running from $target. Quit it, then run installMacApp again.\n$running"
            }

            // ditto keeps the bundle's symlinks, permissions and code signature, but it merges into an existing
            // bundle, so the old copy goes first: leftover jars would break the signature. rm doesn't follow
            // symlinks out of the bundle.
            val removed = ProcessBuilder("/bin/rm", "-rf", target.absolutePath).inheritIO().start().waitFor()
            check(removed == 0) { "Couldn't remove the old $target (rm exit code $removed)" }
            val copied = ProcessBuilder("ditto", builtApp.get().asFile.absolutePath, target.absolutePath)
                .inheritIO().start().waitFor()
            check(copied == 0) { "ditto failed with exit code $copied" }
            println("Installed $target")

            // macOS registers every copy of the app it comes across with LaunchServices, such as the temporary one that
            // each packageDmg run makes and deletes. Registrations of deleted copies can make the Dock show the generic
            // "exec" icon for the running app, so they go, and the installed copy is registered again. The app is
            // installed by now, so problems here only warn.
            val lsregisterPath = "/System/Library/Frameworks/CoreServices.framework/Frameworks/" +
                "LaunchServices.framework/Support/lsregister"
            fun lsregister(vararg args: String): String? = try {
                val process = ProcessBuilder(lsregisterPath, *args)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode != 0) logger.warn("lsregister ${args.joinToString(" ")} failed with exit code $exitCode")
                output.takeIf { exitCode == 0 }
            } catch (e: IOException) {
                logger.warn("Couldn't run lsregister: ${e.message}")
                null
            }
            // The dump has one record per bundle, between lines of dashes, with lines such as
            // "path:   /Applications/Leaf.app (0x2c9c)" and "identifier:   io.github.zzhelev.leaf".
            val pathLine = Regex("""^path:\s+(.+) \(0x\p{XDigit}+\)\s*$""", RegexOption.MULTILINE)
            val identifierLine = Regex("""^identifier:\s+(\S+)\s*$""", RegexOption.MULTILINE)
            val deletedCopies = lsregister("-dump", "Bundle").orEmpty()
                .split(Regex("""^-{10,}$""", RegexOption.MULTILINE))
                .filter { record -> identifierLine.find(record)?.groupValues?.get(1) == bundleId }
                .mapNotNull { record -> pathLine.find(record)?.groupValues?.get(1) }
                .filterNot { path -> File(path).exists() }
            deletedCopies.forEach { path ->
                if (lsregister("-u", path) != null) println("Unregistered the deleted copy $path")
            }
            lsregister("-f", target.absolutePath)
        }
    }
}

// jpackage's .deb needs two fixes, so packageDeb repacks it. Linux only, like packageDeb itself.
// - Its install scripts register the menu entry with xdg-desktop-menu, which fails where there is no system menu
//   directory (/etc/xdg/menus), such as WSL or a minimal install, and leaves the package half configured. The entry
//   becomes a regular file in /usr/share/applications, which dpkg installs and removes like any other, and the
//   xdg-desktop-menu calls go.
// - Its launcher reads the launch data from a pipe with a single read() (JDK-8380085, fixed in JDK 27). Once the user
//   has many pipes open, Linux gives new pipes 8 KB, and longer launch data crashes the launcher with SIGSEGV. Most of
//   Leaf's was the hash Compose adds to each jar name, so the hashes go, except for 8 characters where two jars would
//   otherwise get the same name. The build fails if the classpath still gets too long.
if (currentOs() == OS.LINUX) {
    tasks.withType<AbstractJPackageTask>().matching { it.targetFormat == TargetFormat.Deb }.configureEach {
        val debDir = destinationDir
        val repackDir = temporaryDir.resolve("repack")

        doLast {
            fun runCommand(vararg command: String) {
                val exitCode = ProcessBuilder(*command).inheritIO().start().waitFor()
                check(exitCode == 0) { "${command.joinToString(" ")} failed with exit code $exitCode" }
            }

            val deb = debDir.get().asFile.listFiles { file -> file.extension == "deb" }.orEmpty().singleOrNull()
                ?: error("Expected one .deb in ${debDir.get()}")
            repackDir.deleteRecursively()
            runCommand("dpkg-deb", "--raw-extract", deb.absolutePath, repackDir.absolutePath)

            val postinst = repackDir.resolve("DEBIAN/postinst")
            val prerm = repackDir.resolve("DEBIAN/prerm")
            val entryPath = Regex("""^xdg-desktop-menu install (\S+)$""", RegexOption.MULTILINE)
                .find(postinst.readText())?.groupValues?.get(1)
                ?: error("jpackage's postinst no longer calls xdg-desktop-menu install. Is the repack still needed?")
            val entry = repackDir.resolve(entryPath.removePrefix("/"))
            val appDir = entry.parentFile.resolve("app")
            val cfg = appDir.listFiles { file -> file.extension == "cfg" }.orEmpty().singleOrNull()
                ?: error("Expected one launcher .cfg in $appDir")

            val directoryMode = PosixFilePermissions.fromString("rwxr-xr-x")
            for (directory in listOf("usr", "usr/share", "usr/share/applications")) {
                val path = Files.createDirectories(repackDir.resolve(directory).toPath())
                Files.setPosixFilePermissions(path, directoryMode)
            }
            Files.move(entry.toPath(), repackDir.resolve("usr/share/applications/${entry.name}").toPath())

            for (script in listOf(postinst, prerm)) {
                val lines = script.readLines()
                val kept = lines.filterNot { "xdg-desktop-menu " in it }
                check(kept.size == lines.size - 1) { "Expected one xdg-desktop-menu call in $script" }
                script.writeText(kept.joinToString("\n", postfix = "\n"))
            }

            val hashSuffix = Regex("""-([0-9a-f]{16,32})\.jar$""")
            fun withoutHash(name: String) = name.replace(hashSuffix, ".jar")
            val jars = appDir.listFiles { file -> file.extension == "jar" }.orEmpty().map { it.name }
            val jarsPerName = jars.groupingBy(::withoutHash).eachCount()
            val newNames = jars.associateWith { name ->
                if (jarsPerName.getValue(withoutHash(name)) == 1) {
                    withoutHash(name)
                } else {
                    name.replace(hashSuffix) { "-${it.groupValues[1].take(8)}.jar" }
                }
            }
            check(newNames.values.toSet().size == newNames.size) { "Shortened jar names collide: $newNames" }
            for ((oldName, newName) in newNames) {
                if (oldName != newName) Files.move(appDir.resolve(oldName).toPath(), appDir.resolve(newName).toPath())
            }
            val classpathEntry = "app.classpath=\$APPDIR/"
            cfg.writeText(cfg.readLines().joinToString("\n", postfix = "\n") { line ->
                if (line.startsWith(classpathEntry)) {
                    classpathEntry + newNames.getValue(line.removePrefix(classpathEntry))
                } else {
                    line
                }
            })
            // As the launcher expands $APPDIR. The rest of its launch data (JVM options, paths) is a few hundred bytes.
            val classpathBytes = newNames.values.sumOf { "/${appDir.relativeTo(repackDir)}/$it:".length }
            check(classpathBytes <= 7000) {
                "The classpath is $classpathBytes bytes. Above about 8 KB of launch data in all, jpackage's launcher " +
                    "can crash (JDK-8380085). Shorten the jar names further, or package with a JDK that has the fix."
            }

            runCommand("dpkg-deb", "--root-owner-group", "--build", repackDir.absolutePath, deb.absolutePath)
            println(
                "Repacked $deb: ${entry.name} is in /usr/share/applications, with no xdg-desktop-menu calls, and the " +
                    "classpath is $classpathBytes bytes"
            )
        }
    }
}

tasks.register("fatJarLinux", type = Jar::class) {
    val archSuffix = if (isLinuxAarch64) {
        "arm_aarch64"
    } else {
        "x86_64"
    }

    archiveBaseName.set("$projectName-linux-$archSuffix-$projectVersion")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Implementation-Title"] = name
        attributes["Implementation-Version"] = projectVersion
        attributes["Main-Class"] = "dev.app.leaf.MainKt"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        // Makes AppStorage use Leaf's real storage, not the dev one, although java -jar sets no jpackage property.
        attributes["Leaf-Packaged"] = "true"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
        )
    }
    with(tasks.jar.get() as CopySpec)
}

tasks.register("tasksList") {
    println("Tasks")
    tasks.forEach {
        println("- ${it.name}")
    }
}

val rustProjectDir: File = rootProject.file("rs")

val askpassName = when (currentOs()) {
    OS.WINDOWS -> "leaf-askpass.exe"
    else -> "leaf-askpass"
}

// Only Linux builds use cross, which builds for an explicit target, into target/<target>/.
val rustTarget: String? = when {
    currentOs() == OS.LINUX && useCross -> if (isLinuxAarch64) linuxArmTarget else linuxX64Target
    else -> null
}

val rustOutputDir: File = rustProjectDir.resolve(
    listOfNotNull("target", rustTarget, if (isRustRelease) "release" else "debug").joinToString("/")
)

// What cargo builds from. The toolchain file picks the Rust version.
fun rustSources() = fileTree(rustProjectDir) {
    include("*.rs", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml")
}

// Builds leaf-askpass, the askpass program and credential helper of the git commands that Leaf runs. Gradle runs cargo
// only when the files in rustSources() or the build options change, and a cargo failure fails the build.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "Builds leaf-askpass."
    workingDir(rustProjectDir)
    commandLine(
        buildList {
            add(if (rustTarget != null) "cross" else "cargo")
            add("build")
            if (isRustRelease) add("--release")
            if (rustTarget != null) add("--target=$rustTarget")
        }
    )
    inputs.files(rustSources()).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("release", isRustRelease)
    inputs.property("target", rustTarget.orEmpty())
    outputs.file(rustOutputDir.resolve(askpassName))
}

// Leaf extracts leaf-askpass from its resources and runs it (see AskpassHelper). It's gitignored there.
val copyRustBuild = tasks.register("copyRustBuild") {
    group = "rust"
    description = "Copies leaf-askpass into the app's resources."
    val source = rustOutputDir.resolve(askpassName)
    val target = layout.projectDirectory.file("src/main/resources/$askpassName").asFile
    inputs.files(cargoBuild).withPropertyName("builtFiles")
    outputs.file(target)
    doLast {
        // A new file rather than new bytes in the old one, as a running Leaf may have it open
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // The Rust library that builds copied there until 2026-10-15, which the app would still ship
        target.parentFile.listFiles { file -> "leaf_rs" in file.name }.orEmpty().forEach { it.delete() }
    }
}

tasks.named("processResources") {
    dependsOn(copyRustBuild)
}

tasks.register("rustTasks") {
    group = "rust"
    description = "Builds leaf-askpass into the app's resources."
    dependsOn(copyRustBuild)
}
