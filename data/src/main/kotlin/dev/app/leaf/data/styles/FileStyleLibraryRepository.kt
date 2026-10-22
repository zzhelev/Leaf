// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.styles

import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleFileCodec
import dev.app.leaf.domain.styles.StyleFileRead
import dev.app.leaf.domain.styles.StyleIds
import dev.app.leaf.domain.styles.StyleLibrary
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleLibraryRepository
import dev.app.leaf.domain.styles.UnreadableStyle
import dev.app.leaf.domain.styles.UnreadableStyleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StyleLibrary"
private const val EXTENSION = ".json"

/** A style's file is a few kilobytes; anything far larger isn't one. */
private const val MAX_FILE_SIZE = 1024 * 1024L

/** The folder of the user's styles: `styles` in Leaf's app folder (fork-only). */
class StylesFolder(val path: File) {
    @Inject
    constructor(appFilesManager: AppFilesManager) : this(File(appFilesManager.getAppFolder(), AppStorage.STYLES_FOLDER_NAME))
}

/**
 * The user's styles, one `<id>.json` file each in [StylesFolder] (fork-only). The folder is read when Leaf starts and
 * again on [reload]. A file that can't be read is left out of the styles, logged, and listed in
 * [StyleLibrary.unreadable], so Settings can say so. Files are written whole to a temporary file next to them, then
 * moved into place.
 */
@Singleton
class FileStyleLibraryRepository @Inject constructor(
    private val folder: StylesFolder,
) : StyleLibraryRepository {
    private val lock = Mutex()

    override val library: StateFlow<StyleLibrary>
        field = MutableStateFlow(read())

    override suspend fun reload() = withContext(Dispatchers.IO) {
        lock.withLock { library.value = read() }
    }

    override suspend fun save(definition: StyleDefinition): Either<StyleDefinition, StyleLibraryError> =
        withContext(Dispatchers.IO) {
            if (definition.isBuiltIn) return@withContext Either.Err(StyleLibraryError.BuiltIn(definition.id))

            lock.withLock {
                val file = File(folder.path, definition.id + EXTENSION)
                val written = writeWhole(file, StyleFileCodec.encode(definition))
                library.value = read()

                written?.let { Either.Err(it) } ?: Either.Ok(definition)
            }
        }

    override suspend fun delete(id: String): Either<Unit, StyleLibraryError> = withContext(Dispatchers.IO) {
        if (BuiltInStyles.isBuiltIn(id)) return@withContext Either.Err(StyleLibraryError.BuiltIn(id))

        lock.withLock {
            val file = File(folder.path, id + EXTENSION)
            val result = try {
                Files.deleteIfExists(file.toPath())
                Either.Ok(Unit)
            } catch (ex: IOException) {
                printError(TAG, "Couldn't delete ${file.absolutePath}", ex)
                Either.Err(StyleLibraryError.CantWrite(file.absolutePath, ex.message.orEmpty()))
            }
            library.value = read()
            result
        }
    }

    override suspend fun import(file: File): Either<StyleDefinition, StyleLibraryError> = withContext(Dispatchers.IO) {
        val text = when (val read = readText(file)) {
            is Either.Ok -> read.value
            is Either.Err -> return@withContext Either.Err(StyleLibraryError.CantRead(file.absolutePath, read.error))
        }

        lock.withLock {
            val id = StyleIds.unique(StyleIds.fromName(file.name.removeSuffix(EXTENSION)), takenIds())

            when (val decoded = StyleFileCodec.decode(text, id)) {
                is StyleFileRead.Unreadable -> {
                    printLog(TAG, "Not imported, ${file.absolutePath} isn't a style: ${decoded.reason}, ${decoded.detail}")
                    Either.Err(StyleLibraryError.NotAStyle(file.name, decoded.reason, decoded.detail))
                }

                is StyleFileRead.Read -> {
                    // As it is, so that what this version doesn't know of it stays for a newer one
                    val written = writeWhole(File(folder.path, id + EXTENSION), text)
                    library.value = read()

                    if (written == null) {
                        printLog(TAG, "Imported ${file.absolutePath} as $id")
                        Either.Ok(decoded.definition)
                    } else {
                        Either.Err(written)
                    }
                }
            }
        }
    }

    override suspend fun export(definition: StyleDefinition, file: File): Either<Unit, StyleLibraryError> =
        withContext(Dispatchers.IO) {
            writeWhole(file, StyleFileCodec.encode(definition))?.let { Either.Err(it) } ?: Either.Ok(Unit)
        }

    override fun newId(name: String): String = StyleIds.unique(StyleIds.fromName(name), takenIds())

    /** The styles' ids, and the names of every file in the folder, readable or not, so that none is written over. */
    private fun takenIds(): Set<String> =
        library.value.styles.map { it.id }.toSet() + styleFiles().map { it.name.removeSuffix(EXTENSION) }

    private fun styleFiles(): List<File> =
        folder.path.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }?.sortedBy { it.name }.orEmpty()

    private fun read(): StyleLibrary {
        val styles = mutableListOf<StyleDefinition>()
        val unreadable = mutableListOf<UnreadableStyleFile>()

        for (file in styleFiles()) {
            val id = file.name.removeSuffix(EXTENSION)

            if (BuiltInStyles.isBuiltIn(id)) {
                printLog(TAG, "Left out ${file.name}: it would hide the built-in style $id")
                unreadable += UnreadableStyleFile(file.name, UnreadableStyle.BUILT_IN_ID, id)
                continue
            }

            val text = when (val read = readText(file)) {
                is Either.Ok -> read.value
                is Either.Err -> {
                    unreadable += UnreadableStyleFile(file.name, UnreadableStyle.CANT_READ, read.error)
                    continue
                }
            }

            when (val decoded = StyleFileCodec.decode(text, id)) {
                is StyleFileRead.Read -> {
                    decoded.problems.forEach { printLog(TAG, "${file.name}: $it") }
                    styles += decoded.definition
                }

                is StyleFileRead.Unreadable -> {
                    printLog(TAG, "Left out ${file.name}: ${decoded.reason}, ${decoded.detail}")
                    unreadable += UnreadableStyleFile(file.name, decoded.reason, decoded.detail)
                }
            }
        }

        return StyleLibrary(styles, unreadable)
    }

    private fun readText(file: File): Either<String, String> = try {
        if (file.length() > MAX_FILE_SIZE) {
            Either.Err("it's larger than 1 MB")
        } else {
            Either.Ok(file.readText())
        }
    } catch (ex: IOException) {
        printError(TAG, "Couldn't read ${file.absolutePath}", ex)
        Either.Err(ex.message.orEmpty())
    }

    /** Writes [text] to [file] through a temporary file in its folder, or gives why it couldn't. */
    private fun writeWhole(file: File, text: String): StyleLibraryError? {
        val parent = file.absoluteFile.parentFile
        var temporary: File? = null

        return try {
            parent.mkdirs()
            temporary = File.createTempFile(".${file.name}-", ".tmp", parent)
            temporary.writeText(text)

            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (ex: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            null
        } catch (ex: IOException) {
            printError(TAG, "Couldn't write ${file.absolutePath}", ex)
            temporary?.delete()
            StyleLibraryError.CantWrite(file.absolutePath, ex.message.orEmpty())
        }
    }
}
