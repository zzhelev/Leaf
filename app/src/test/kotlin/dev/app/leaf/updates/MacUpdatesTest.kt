// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installing an update on macOS (step 3 of docs/fork/auto-update.md): the DMG's app copied and checked, and the script
 * that swaps it in once Leaf has quit, against app bundles in a temporary folder. `open` and `lsregister` are stand-ins
 * that note their arguments, so that LaunchServices never sees a test's app; the test plays the new Leaf.
 */
class MacUpdatesTest {
    @TempDir
    lateinit var dir: File

    private val updates get() = File(dir, "updates")
    private val apps get() = File(dir, "apps").apply { mkdirs() }
    private val opened get() = File(dir, "opened.log")
    private val registered get() = File(dir, "registered.log")
    private val leafProcesses = mutableListOf<Process>()

    @AfterEach
    fun tearDown() {
        leafProcesses.forEach { it.destroyForcibly() }
        apps.setWritable(true)
    }

    // Where Leaf is

    @Test
    fun `the bundle comes from jpackage's launcher, else from its runtime, and a dev run has none`() {
        assertEquals(File("/Applications/Leaf.app"), macBundleOf("/Applications/Leaf.app/Contents/MacOS/Leaf", null))
        assertEquals(
            File("/Users/a/Apps/Leaf.app"),
            macBundleOf(null, "/Users/a/Apps/Leaf.app/Contents/runtime/Contents/Home"),
        )
        assertNull(macBundleOf(null, "/Users/a/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"))
        assertNull(macBundleOf("/opt/leaf/bin/Leaf", "/opt/leaf/lib/runtime"))
        assertNull(macBundleOf(null, null))
    }

    @Test
    fun `a bundle that macOS translocated, or whose folder can't be written, is installed by hand`() {
        val bundle = File("/Applications/Leaf.app")

        assertEquals(MacInstallPlace.Updatable(bundle), macInstallPlace(bundle) { true })
        assertInstanceOf(
            MacInstallPlace.ByHand::class.java,
            macInstallPlace(File("/private/var/folders/x/AppTranslocation/ABC/d/Leaf.app")) { true },
        )
        val notWritable = assertInstanceOf(MacInstallPlace.ByHand::class.java, macInstallPlace(bundle) { it != bundle.parentFile })
        assertTrue(notWritable.reason.contains("/Applications"), notWritable.reason)
        assertInstanceOf(MacInstallPlace.ByHand::class.java, macInstallPlace(null))
    }

    // Preparing

    @Test
    @EnabledOnOs(OS.MAC)
    fun `the DMG's Leaf is copied next to the installed one, checked, and the DMG deleted`(): Unit = runBlocking {
        val installed = fakeApp(apps, version = "1.10.0")
        val dmg = dmgWith(fakeApp(File(dir, "release"), version = "1.11.0"))

        val prepared = assertInstanceOf(MacPrepared.Ready::class.java, macUpdates().prepare(dmg, installed, "1.11.0"))

        assertEquals(File(apps, ".Leaf-1.11.0.app"), prepared.copy)
        assertEquals("1.11.0", plistValue(prepared.copy, "CFBundleShortVersionString"))
        assertFalse(dmg.exists())
        assertNothingMounted()
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a DMG whose app isn't Leaf, isn't the version, or isn't signed as built is refused, and nothing is kept`(): Unit =
        runBlocking {
            val installed = fakeApp(apps, version = "1.10.0")

            for ((app, says) in listOf(
                fakeApp(File(dir, "other-id"), id = "com.example.other") to "not Leaf's",
                fakeApp(File(dir, "other-version"), version = "1.12.0") to "is version 1.12.0, not 1.11.0",
                fakeApp(File(dir, "changed")).also { File(it, "Contents/Resources/notes.txt").appendText("changed") } to
                    "isn't signed as it was built",
            )) {
                val dmg = dmgWith(app)
                val refused = assertInstanceOf(MacPrepared.Refused::class.java, macUpdates().prepare(dmg, installed, "1.11.0"))

                assertTrue(refused.reason.contains(says), refused.reason)
                assertFalse(File(apps, ".Leaf-1.11.0.app").exists())
                assertTrue(dmg.exists())
                assertNothingMounted()
            }
        }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a DMG without Leaf, or a file that isn't a DMG, keeps nothing`(): Unit = runBlocking {
        val installed = fakeApp(apps, version = "1.10.0")
        val empty = File(dir, "empty").apply { mkdirs(); File(this, "README").writeText("no app") }
        val withoutLeaf = dmgOf(empty)

        val refused = assertInstanceOf(MacPrepared.Refused::class.java, macUpdates().prepare(withoutLeaf, installed, "1.11.0"))
        assertTrue(refused.reason.contains("has no Leaf.app"), refused.reason)

        val notADmg = File(dir, "Leaf-1.11.0-macos-arm64.dmg").apply { writeText("not a disk image") }
        assertInstanceOf(MacPrepared.Failed::class.java, macUpdates().prepare(notADmg, installed, "1.11.0"))

        assertFalse(File(apps, ".Leaf-1.11.0.app").exists())
        assertNothingMounted()
    }

    // Installing

    @Test
    @EnabledOnOs(OS.MAC)
    fun `once Leaf quits, the new app takes its place, is opened, and the new Leaf deletes the old`() {
        val (bundle, copy) = installedAndPrepared()
        val leaf = leafProcess()
        val macUpdates = macUpdates()

        macUpdates.startInstall(bundle, copy, "1.10.0", "1.11.0", leaf.pid())
        Thread.sleep(500)
        assertEquals("1.10.0", markerOf(bundle), "nothing moves while Leaf runs")

        leaf.destroy()
        awaitOpened(1)
        // The new Leaf starts
        macUpdates.finishAtStart("1.11.0", bundle)
        awaitLog("updated to Leaf 1.11.0")

        assertEquals("1.11.0", markerOf(bundle))
        assertEquals(listOf("-f ${bundle.path}"), registered.readLines())
        assertEquals(listOf(bundle.path), opened.readLines())
        assertEquals(listOf("Leaf.app"), apps.list()!!.toList())
        assertEquals(InstallOutcome("1.10.0", "1.11.0", isUpdated = true), macUpdates.takeOutcome())
        assertNull(macUpdates.takeOutcome(), "told once")
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a new Leaf that doesn't start is put aside, and the old one comes back and says why`() {
        val (bundle, copy) = installedAndPrepared()
        val leaf = leafProcess()
        val macUpdates = macUpdates()

        macUpdates.startInstall(bundle, copy, "1.10.0", "1.11.0", leaf.pid())
        leaf.destroy()
        // The script says why before it opens the old Leaf again
        awaitLog("failed: ")
        awaitOpened(2)

        assertEquals("1.10.0", markerOf(bundle))
        assertEquals("1.11.0", markerOf(File(apps, ".Leaf-1.11.0-failed.app")))
        assertEquals(listOf(bundle.path, bundle.path), opened.readLines())

        // The old Leaf starts
        macUpdates.finishAtStart("1.10.0", bundle)

        assertEquals(listOf("Leaf.app"), apps.list()!!.toList())
        assertEquals(
            InstallOutcome("1.10.0", "1.11.0", isUpdated = false, reason = "Leaf 1.11.0 didn't start, so Leaf 1.10.0 is back."),
            macUpdates.takeOutcome(),
        )
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a new Leaf that starts slowly is waited for, not undone`() {
        val (bundle, copy) = installedAndPrepared()
        val leaf = leafProcess()
        val macUpdates = macUpdates(open = startingOpen())

        macUpdates.startInstall(bundle, copy, "1.10.0", "1.11.0", leaf.pid())
        leaf.destroy()
        awaitOpened(1)
        Thread.sleep(2_500)
        assertTrue(File(apps, ".Leaf-1.10.0-old.app").exists(), "still waiting past the start's wait")
        macUpdates.finishAtStart("1.11.0", bundle)
        awaitLog("updated to Leaf 1.11.0")

        assertEquals("1.11.0", markerOf(bundle))
        assertFalse(File(apps, ".Leaf-1.11.0-failed.app").exists())
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a bundle that can't be moved stays, and opens again`() {
        val (bundle, copy) = installedAndPrepared()
        val leaf = leafProcess()
        val macUpdates = macUpdates()
        apps.setWritable(false)

        macUpdates.startInstall(bundle, copy, "1.10.0", "1.11.0", leaf.pid())
        leaf.destroy()
        awaitLog("failed: ")
        awaitOpened(1)
        apps.setWritable(true)

        assertEquals("1.10.0", markerOf(bundle))
        assertEquals(listOf(bundle.path), opened.readLines())
        macUpdates.finishAtStart("1.10.0", bundle)
        assertEquals(
            InstallOutcome("1.10.0", "1.11.0", isUpdated = false, reason = "Leaf couldn't move ${bundle.path} aside."),
            macUpdates.takeOutcome(),
        )
        assertEquals(listOf("Leaf.app"), apps.list()!!.toList())
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a Leaf that doesn't quit is left as it is`() {
        val (bundle, copy) = installedAndPrepared()
        val leaf = leafProcess()

        macUpdates().startInstall(bundle, copy, "1.10.0", "1.11.0", leaf.pid())
        awaitLog("failed: Leaf 1.10.0 didn't quit.")

        assertEquals("1.10.0", markerOf(bundle))
        assertEquals("1.11.0", markerOf(copy))
        assertFalse(opened.exists())
    }

    @Test
    fun `a start of another version leaves the install as it is, and a record that can't be read goes`() {
        val macUpdates = macUpdates()
        val bundle = File(apps, "Leaf.app")
        updates.mkdirs()
        val record = File(updates, MacUpdates.INSTALL_RECORD)
        record.writeText(
            """{"from":"1.10.0","to":"1.11.0","bundle":"${bundle.path}","prepared":"p","old":"o","failed":"f"}"""
        )

        macUpdates.finishAtStart("1.12.0", bundle)
        assertTrue(record.exists())
        macUpdates.finishAtStart("1.11.0", File(dir, "Elsewhere.app"))
        assertTrue(record.exists(), "another copy of the same version")

        record.writeText("{ not json")
        macUpdates.finishAtStart("1.11.0", bundle)
        assertFalse(record.exists())
        assertNull(macUpdates.takeOutcome())
    }

    // Helpers

    private fun macUpdates(open: String = recordingScript("open", opened)) = MacUpdates(
        updatesFolder = { updates },
        lsregister = recordingScript("lsregister", registered),
        open = open,
        waits = MacInstallWaits(forQuit = 2, forStart = 1, forSlowStart = 10),
    )

    /** A stand-in that notes its arguments, a call a line. */
    private fun recordingScript(name: String, log: File): String = File(dir, "bin/$name").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\necho \"$*\" >> '${log.path}'\n")
        setExecutable(true)
    }.path

    /**
     * A stand-in for open that also starts a process whose command line names the bundle's launcher, as the new Leaf's
     * does, which is what the script looks for. Nothing runs from inside the bundle: macOS's Gatekeeper kills a program
     * in a folder named `*.app` that isn't a signed app, and shows "“Leaf” is damaged" (found on 2026-11-27). The `:` keeps
     * the shell from replacing itself with sleep, whose command line wouldn't name the bundle.
     */
    private fun startingOpen(): String = File(dir, "bin/open-starting").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\necho \"$*\" >> '${opened.path}'\nsh -c 'sleep 6; :' \"$1/Contents/MacOS/Leaf\" > /dev/null 2>&1 &\n")
        setExecutable(true)
    }.path

    /** The installed Leaf 1.10.0 and the prepared 1.11.0 next to it, as folders that say which they are. */
    private fun installedAndPrepared(): Pair<File, File> {
        val bundle = File(apps, "Leaf.app").apply { File(this, "Contents/MacOS").mkdirs(); File(this, "version").writeText("1.10.0") }
        val copy = File(apps, ".Leaf-1.11.0.app").apply { File(this, "Contents/MacOS").mkdirs(); File(this, "version").writeText("1.11.0") }

        return bundle to copy
    }

    private fun markerOf(bundle: File) = File(bundle, "version").readText()

    /** Stands in for the running Leaf: a process that runs until it's ended. */
    private fun leafProcess(): Process = ProcessBuilder("sleep", "60").start().also { leafProcesses += it }

    private fun awaitOpened(count: Int) = await("open called $count times") { opened.isFile && opened.readLines().size >= count }

    private fun awaitLog(line: String) = await("the log saying $line") {
        File(updates, MacUpdates.INSTALL_LOG).let { it.isFile && it.readText().contains(line) }
    }

    private fun await(what: String, until: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)

        while (!until()) {
            check(System.nanoTime() < end) {
                "No $what. The log: " + File(updates, MacUpdates.INSTALL_LOG).takeIf { it.isFile }?.readText()
            }
            Thread.sleep(50)
        }
    }

    /** An app as jpackage makes Leaf, as far as the checks go: signed ad hoc, with a bundle ID and a version. */
    private fun fakeApp(folder: File, id: String = MAC_BUNDLE_ID, version: String = "1.11.0"): File {
        val app = File(folder, "Leaf.app")
        File(app, "Contents/MacOS").mkdirs()
        File(app, "Contents/Resources").mkdirs()
        File("/usr/bin/true").copyTo(File(app, "Contents/MacOS/Leaf")).setExecutable(true)
        File(app, "Contents/Resources/notes.txt").writeText("A resource that the signature seals\n")
        File(app, "Contents/Info.plist").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleExecutable</key><string>Leaf</string>
                <key>CFBundleIdentifier</key><string>$id</string>
                <key>CFBundleName</key><string>Leaf</string>
                <key>CFBundlePackageType</key><string>APPL</string>
                <key>CFBundleShortVersionString</key><string>$version</string>
            </dict>
            </plist>
            """.trimIndent() + "\n"
        )
        run("codesign", "--force", "--deep", "-s", "-", app.path)

        return app
    }

    /** A DMG with [app] in it, as the release's has Leaf.app. Changes made after signing stay. */
    private fun dmgWith(app: File): File {
        val source = File(dir, "dmg-source-${System.nanoTime()}").apply { mkdirs() }
        run("ditto", app.path, File(source, "Leaf.app").path)

        return dmgOf(source)
    }

    private fun dmgOf(source: File): File {
        val dmg = File(dir, "Leaf-${System.nanoTime()}.dmg")
        run("hdiutil", "create", "-quiet", "-srcfolder", source.path, "-volname", "Leaf", "-format", "UDZO", "-ov", "-o", dmg.path)

        return dmg
    }

    private fun plistValue(app: File, key: String): String =
        run("/usr/libexec/PlistBuddy", "-c", "Print :$key", File(app, "Contents/Info.plist").path).trim()

    private fun assertNothingMounted() {
        assertFalse(run("mount").contains(updates.canonicalPath), "a mount is left")
        assertEquals(emptyList<String>(), updates.list()?.filter { it.startsWith("mount-") }.orEmpty())
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.joinToString(" ")}: $output" }

        return output
    }
}
