// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsObjectDownloadError
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictPreview
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.VersionPreview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the resolver's cards show once a Git LFS object's download ends. */
class LfsDownloadsTest {
    private val photo = LfsObject("a".repeat(64), 4)
    private val other = LfsObject("b".repeat(64), 4)

    @Test
    fun `a downloaded object shows on every card that has it, base included`() {
        val downloaded = VersionPreview.Lfs(photo, downloaded = true, image = PreviewImage("png".toByteArray()))
        val file = file(
            base = VersionPreview.Lfs(photo, downloaded = false),
            ours = VersionPreview.Lfs(other, downloaded = false),
            theirs = VersionPreview.Lfs(photo, downloaded = false),
        )

        assertEquals(
            ConflictPreview(
                base = downloaded,
                ours = VersionPreview.Lfs(other, downloaded = false),
                theirs = downloaded,
            ),
            file.withLfsVersion(downloaded).preview,
        )
    }

    @Test
    fun `versions that aren't LFS objects stay as they are`() {
        val file = file(base = null, ours = VersionPreview.File(4), theirs = null)

        assertEquals(file, file.withLfsVersion(VersionPreview.Lfs(photo, downloaded = true)))

        val withoutPreview = file.copy(preview = null)
        assertEquals(withoutPreview, withoutPreview.withLfsVersion(VersionPreview.Lfs(photo, downloaded = true)))
    }

    @Test
    fun `closing the credentials dialog puts Download back, and any other failure shows`() {
        assertNull(lfsDownloadFailure(LfsObjectDownloadError(LfsError.CredentialsDenied)))

        val refused = LfsObjectDownloadError(LfsError.ObjectRefused(404, "Object does not exist"))
        assertEquals(LfsDownload.Failed(refused), lfsDownloadFailure(refused))
        assertEquals(LfsDownload.Failed(GenericError("Cancelled")), lfsDownloadFailure(GenericError("Cancelled")))
    }

    private fun file(base: VersionPreview?, ours: VersionPreview?, theirs: VersionPreview?) = ConflictedFile(
        path = "photo.png",
        base = ConflictStage("1".repeat(40), 0b1000000110100100),
        ours = ConflictStage("2".repeat(40), 0b1000000110100100),
        theirs = ConflictStage("3".repeat(40), 0b1000000110100100),
        conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
        sides = null,
        content = ConflictContent.Lfs(photo, photo),
        preview = ConflictPreview(base, ours, theirs),
    )
}
