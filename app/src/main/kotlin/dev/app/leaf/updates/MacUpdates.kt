// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.AppFilesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MacUpdates"

/** Leaf's bundle ID, which the copy in a release's DMG must have (`bundleID` in app/build.gradle.kts). */
const val MAC_BUNDLE_ID = "io.github.zzhelev.leaf"

private const val LSREGISTER =
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

/**
 * The app bundle that runs this Leaf, from jpackage's layout: the launcher, `<bundle>/Contents/MacOS/Leaf`
 * (`jpackage.app-path`), else `java.home`, `<bundle>/Contents/runtime/Contents/Home`. Anything else, such as a dev run on
 * IntelliJ's runtime (`IntelliJ IDEA.app/Contents/jbr/Contents/Home`), has none.
 */
fun macBundleOf(launcher: String?, javaHome: String?): File? {
    fun bundleAbove(path: String?, vararg below: String): File? {
        var file = path?.let(::File) ?: return null

        for (name in below.reversed()) {
            if (name != "*" && file.name != name) return null
            file = file.parentFile ?: return null
        }

        return file.takeIf { it.name.endsWith(".app") }
    }

    return bundleAbove(launcher, "Contents", "MacOS", "*")
        ?: bundleAbove(javaHome, "Contents", "runtime", "Contents", "Home")
}

/** Whether Leaf can swap the bundle that runs it for an update's, or the user installs the DMG by hand. */
sealed interface MacInstallPlace {
    data class Updatable(val bundle: File) : MacInstallPlace

    /** [reason] says why, for the user, who opens the DMG and drags Leaf to Applications. */
    data class ByHand(val reason: String) : MacInstallPlace
}

fun macInstallPlace(bundle: File?, canWrite: (File) -> Boolean = { Files.isWritable(it.toPath()) }): MacInstallPlace {
    val folder = bundle?.parentFile

    return when {
        bundle == null || folder == null -> MacInstallPlace.ByHand("Leaf can't tell where it's installed.")
        // A quarantined app opened from Downloads runs from a read-only copy that macOS made
        "/AppTranslocation/" in bundle.path -> MacInstallPlace.ByHand(
            "macOS runs this Leaf from a copy of its own, as it was opened where it was downloaded."
        )

        !canWrite(folder) || !canWrite(bundle) -> MacInstallPlace.ByHand("Leaf can't write to ${folder.path}.")
        else -> MacInstallPlace.Updatable(bundle)
    }
}

/** What preparing a DMG's Leaf.app came to. */
sealed interface MacPrepared {
    /** [copy] is the release's Leaf.app, next to the installed one, checked. */
    data class Ready(val copy: File) : MacPrepared

    /** The DMG's app isn't the release's: [reason] says why, for the user. Nothing was kept. */
    data class Refused(val reason: String) : MacPrepared

    /** The DMG couldn't be mounted or copied from: [reason] says why. Nothing was kept. */
    data class Failed(val reason: String) : MacPrepared
}

/** An update that the script installs once Leaf has quit, kept in `install.json` for the next start. */
@Serializable
data class MacInstallRecord(
    val from: String,
    val to: String,
    val bundle: String,
    /** The release's app, next to [bundle], which the script moves into its place. */
    val prepared: String,
    /** Where the script moves the installed app, which the new Leaf deletes once it runs. */
    val old: String,
    /** Where the script moves the new app when it didn't start, putting [old] back. */
    val failed: String,
)

/** What the last install came to, for the next start to tell the user. */
@Serializable
data class InstallOutcome(val from: String, val to: String, val isUpdated: Boolean, val reason: String? = null)

/** How the script waits, in seconds: tests make them short. */
data class MacInstallWaits(val forQuit: Int = 120, val forStart: Int = 30, val forSlowStart: Int = 120)

/**
 * Installs a release's DMG over the macOS app that runs Leaf (fork-only, docs/fork/auto-update.md, step 3):
 * [prepare] copies the DMG's Leaf.app next to the installed one and checks it, [startInstall] starts the script that
 * swaps the two once Leaf has quit, and [finishAtStart], early in the next start, completes it.
 */
@Singleton
class MacUpdates(
    private val updatesFolder: () -> File,
    private val lsregister: String = LSREGISTER,
    private val open: String = "/usr/bin/open",
    private val waits: MacInstallWaits = MacInstallWaits(),
) {
    @Inject
    constructor(appFilesManager: AppFilesManager) : this(
        updatesFolder = { File(appFilesManager.getAppFolder(), ReleaseUpdates.UPDATES_FOLDER_NAME) },
    )

    private val record get() = File(updatesFolder(), INSTALL_RECORD)
    private val outcome get() = File(updatesFolder(), INSTALL_OUTCOME)
    private val log get() = File(updatesFolder(), INSTALL_LOG)

    /**
     * Copies Leaf.app from [dmg] next to [bundle], as `.Leaf-<version>.app`, and checks the copy: its signature
     * (`codesign --verify --deep --strict`), Leaf's bundle ID and [version]. The DMG is mounted out of sight, and
     * deleted once the copy checks out.
     */
    suspend fun prepare(dmg: File, bundle: File, version: String): MacPrepared = withContext(Dispatchers.IO) {
        val copy = File(bundle.parentFile, ".Leaf-$version.app")
        val mountPoint = Files.createTempDirectory(updatesFolder().apply { mkdirs() }.toPath(), "mount-").toFile()
        var isReady = false

        try {
            val attach = if (run("diskutil", "image").exitCode == 0) {
                run("diskutil", "image", "attach", "--mountOptions", "nobrowse", "--readOnly", "--mountPoint", mountPoint.path, dmg.path)
            } else {
                run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mountPoint.path, dmg.path)
            }

            if (attach.exitCode != 0) {
                printError(TAG, "Mounting $dmg failed: ${attach.output}")
                return@withContext MacPrepared.Failed("Leaf couldn't open ${dmg.name}.")
            }

            val result = try {
                copyAndCheck(File(mountPoint, "Leaf.app"), copy, dmg, version)
            } finally {
                detach(mountPoint)
            }

            if (result is MacPrepared.Ready) {
                isReady = true
                dmg.delete()
            }

            result
        } finally {
            if (!isReady) delete(copy)
            mountPoint.delete()
        }
    }

    private fun copyAndCheck(app: File, copy: File, dmg: File, version: String): MacPrepared {
        if (!File(app, "Contents/Info.plist").isFile) {
            return refused("${dmg.name} has no Leaf.app.")
        }

        delete(copy)
        val ditto = run("ditto", app.path, copy.path, timeoutSeconds = 600)

        if (ditto.exitCode != 0) {
            printError(TAG, "Copying $app failed: ${ditto.output}")
            return MacPrepared.Failed("Leaf couldn't copy Leaf.app out of ${dmg.name}: ${ditto.output.trim()}")
        }

        val signature = run("codesign", "--verify", "--deep", "--strict", copy.path, timeoutSeconds = 300)

        if (signature.exitCode != 0) {
            printError(TAG, "The signature of $copy doesn't check out: ${signature.output}")
            return refused("The Leaf.app in ${dmg.name} isn't signed as it was built.")
        }

        val plist = File(copy, "Contents/Info.plist").path
        val id = run("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", plist).output.trim()
        val appVersion = run("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", plist).output.trim()

        return when {
            id != MAC_BUNDLE_ID -> refused("The Leaf.app in ${dmg.name} has the bundle ID $id, not Leaf's.")
            appVersion != version -> refused("The Leaf.app in ${dmg.name} is version $appVersion, not $version.")
            else -> {
                printLog(TAG, "Prepared $copy")
                MacPrepared.Ready(copy)
            }
        }
    }

    private fun detach(mountPoint: File) {
        if (run("hdiutil", "detach", mountPoint.path, "-quiet").exitCode != 0) {
            val forced = run("hdiutil", "detach", mountPoint.path, "-force", "-quiet")

            if (forced.exitCode != 0) printError(TAG, "Unmounting $mountPoint failed: ${forced.output}")
        }
    }

    /**
     * Starts the script that installs [copy] over [bundle] once this Leaf (its process) has quit, and keeps what it
     * does in `install.json`, for the next start. The caller then quits Leaf.
     */
    fun startInstall(bundle: File, copy: File, from: String, to: String, pid: Long = ProcessHandle.current().pid()) {
        val folder = updatesFolder().apply { mkdirs() }
        val install = MacInstallRecord(
            from = from,
            to = to,
            bundle = bundle.path,
            prepared = copy.path,
            old = File(bundle.parentFile, ".Leaf-$from-old.app").path,
            failed = File(bundle.parentFile, ".Leaf-$to-failed.app").path,
        )
        record.writeText(json.encodeToString(MacInstallRecord.serializer(), install))
        outcome.delete()
        val script = File(folder, INSTALL_SCRIPT).apply { writeText(MAC_INSTALL_SCRIPT) }
        log.writeText("")

        printLog(TAG, "Installing Leaf $to over $bundle once Leaf quits")

        ProcessBuilder(
            "/bin/sh", script.path, pid.toString(), install.bundle, install.prepared, install.old, install.failed,
            from, to, log.path,
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .also {
                val environment = it.environment()
                environment["LEAF_UPDATE_LSREGISTER"] = lsregister
                environment["LEAF_UPDATE_OPEN"] = open
                environment["LEAF_UPDATE_WAIT_QUIT"] = waits.forQuit.toString()
                environment["LEAF_UPDATE_WAIT_START"] = waits.forStart.toString()
                environment["LEAF_UPDATE_WAIT_SLOW_START"] = waits.forSlowStart.toString()
            }
            .start()
    }

    /**
     * Completes an install that the script did, early in a start: a new Leaf from the installed bundle deletes the old
     * copy, which tells the script that it runs; the Leaf that was there before, back after a failed install, deletes
     * what the install left. Either way, what came of it is kept for [takeOutcome].
     */
    fun finishAtStart(version: String, bundle: File?) {
        val install = readRecord() ?: return

        when {
            version == install.to && bundle?.path == install.bundle -> {
                delete(File(install.old))
                delete(File(install.failed))
                writeOutcome(InstallOutcome(install.from, install.to, isUpdated = true))
                printLog(TAG, "Updated from Leaf ${install.from} to ${install.to}")
            }

            version == install.from -> {
                delete(File(install.prepared))
                delete(File(install.failed))
                val reason = log.takeIf { it.isFile }?.readLines()
                    ?.lastOrNull { it.contains("failed: ") }?.substringAfter("failed: ")
                    ?: "Leaf ${install.to} wasn't installed."
                writeOutcome(InstallOutcome(install.from, install.to, isUpdated = false, reason = reason))
                printError(TAG, "Updating to Leaf ${install.to} failed: $reason")
            }

            // Another Leaf, such as a dev run that shares nothing with it, leaves it
            else -> return
        }

        record.delete()
    }

    /** What the last install came to, once: the next call has none. */
    fun takeOutcome(): InstallOutcome? {
        val file = outcome
        val read = try {
            if (file.isFile) json.decodeFromString(InstallOutcome.serializer(), file.readText()) else null
        } catch (e: SerializationException) {
            null
        }

        file.delete()

        return read
    }

    private fun readRecord(): MacInstallRecord? = try {
        record.takeIf { it.isFile }?.let { json.decodeFromString(MacInstallRecord.serializer(), it.readText()) }
    } catch (e: SerializationException) {
        printError(TAG, "Can't read ${record.path}: $e")
        record.delete()
        null
    }

    private fun writeOutcome(value: InstallOutcome) {
        outcome.writeText(json.encodeToString(InstallOutcome.serializer(), value))
    }

    private fun refused(reason: String): MacPrepared.Refused {
        printError(TAG, reason)

        return MacPrepared.Refused(reason)
    }

    companion object {
        const val INSTALL_RECORD = "install.json"
        const val INSTALL_OUTCOME = "install-outcome.json"
        const val INSTALL_LOG = "install.log"
        const val INSTALL_SCRIPT = "install-macos.sh"
    }
}

private val json = Json { ignoreUnknownKeys = true }

private data class CommandResult(val exitCode: Int, val output: String)

private fun run(vararg command: String, timeoutSeconds: Long = 60): CommandResult = try {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .start()
    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)

    if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        CommandResult(process.exitValue(), output)
    } else {
        process.destroyForcibly()
        CommandResult(-1, "${command.first()} didn't end in $timeoutSeconds s")
    }
} catch (e: IOException) {
    CommandResult(-1, e.message ?: e.toString())
}

/** Deletes [file], a folder with what's in it, without following links. */
private fun delete(file: File) {
    if (file.exists() || Files.isSymbolicLink(file.toPath())) {
        FileUtils.delete(file, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
    }
}

/**
 * The script that installs a prepared app once Leaf has quit. Its arguments: Leaf's process, the installed bundle, the
 * prepared copy, where the installed bundle goes aside, where a new app that didn't start goes, both versions, and its
 * log. A line `failed: <reason>` in the log is what the next start tells the user.
 */
private val MAC_INSTALL_SCRIPT = """
    #!/bin/sh
    # Leaf's macOS update (docs/fork/auto-update.md, step 3), written by Leaf into its updates folder
    pid=${'$'}1; bundle=${'$'}2; prepared=${'$'}3; old=${'$'}4; failed=${'$'}5; from=${'$'}6; to=${'$'}7; log=${'$'}8
    lsregister=${'$'}{LEAF_UPDATE_LSREGISTER:?}; open=${'$'}{LEAF_UPDATE_OPEN:?}

    say() { echo "${'$'}(date '+%H:%M:%S') ${'$'}*" >> "${'$'}log"; }
    running() { pgrep -f "${'$'}bundle/Contents/MacOS/" > /dev/null 2>&1; }

    # 1. Leaf quits
    waited=0
    while kill -0 "${'$'}pid" 2> /dev/null; do
        if [ "${'$'}waited" -ge "${'$'}((LEAF_UPDATE_WAIT_QUIT * 5))" ]; then say "failed: Leaf ${'$'}from didn't quit."; exit 1; fi
        sleep 0.2; waited=${'$'}((waited + 1))
    done
    say "Leaf ${'$'}from quit"

    # 2. The swap: renames on one volume, which are all or nothing
    if ! mv "${'$'}bundle" "${'$'}old" >> "${'$'}log" 2>&1; then
        say "failed: Leaf couldn't move ${'$'}bundle aside."
        "${'$'}open" "${'$'}bundle"; exit 1
    fi
    if ! mv "${'$'}prepared" "${'$'}bundle" >> "${'$'}log" 2>&1; then
        mv "${'$'}old" "${'$'}bundle" >> "${'$'}log" 2>&1
        say "failed: Leaf couldn't move Leaf ${'$'}to into place."
        "${'$'}open" "${'$'}bundle"; exit 1
    fi
    "${'$'}lsregister" -f "${'$'}bundle" >> "${'$'}log" 2>&1
    say "Leaf ${'$'}to is in place"
    "${'$'}open" "${'$'}bundle" >> "${'$'}log" 2>&1

    # 3. The new Leaf deletes the old copy once it runs. One that's still starting is waited for, not undone
    waited=0
    while [ -e "${'$'}old" ]; do
        if [ "${'$'}waited" -ge "${'$'}((LEAF_UPDATE_WAIT_START * 5))" ]; then
            if running && [ "${'$'}waited" -lt "${'$'}(((LEAF_UPDATE_WAIT_START + LEAF_UPDATE_WAIT_SLOW_START) * 5))" ]; then
                :
            elif running; then
                say "Leaf ${'$'}to runs, and hasn't deleted Leaf ${'$'}from yet"; exit 0
            else
                mv "${'$'}bundle" "${'$'}failed" >> "${'$'}log" 2>&1
                mv "${'$'}old" "${'$'}bundle" >> "${'$'}log" 2>&1
                "${'$'}lsregister" -f "${'$'}bundle" >> "${'$'}log" 2>&1
                say "failed: Leaf ${'$'}to didn't start, so Leaf ${'$'}from is back."
                "${'$'}open" "${'$'}bundle"; exit 1
            fi
        fi
        sleep 0.2; waited=${'$'}((waited + 1))
    done
    say "updated to Leaf ${'$'}to"
""".trimIndent() + "\n"
