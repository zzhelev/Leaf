// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * What the choice cards show of a conflict that three panes can't show (`conflict-resolver.md`, 2.4, step 5): each
 * version at the path, and what the kind of conflict adds.
 */
data class ConflictPreview(
    /** Null without a base, as when both sides added the path. */
    val base: VersionPreview?,
    /** Null where ours deleted the path. */
    val ours: VersionPreview?,
    /** Null where theirs deleted the path. */
    val theirs: VersionPreview?,
    /**
     * A file that one side deleted: what the version kept changes from the base, as Keep stages it. Null when it isn't
     * text, or is too large to compare.
     */
    val kept: KeptChanges? = null,
    /**
     * A file that one side deleted: the working file, which Keep stages, isn't the kept side's version any more. It was
     * edited after the merge stopped.
     */
    val workingFileEdited: Boolean = false,
    /** A submodule: how the two commits relate, and what the submodule has checked out. */
    val submodule: SubmodulePreview? = null,
)

/** One version of the path, as a choice card shows it. */
sealed interface VersionPreview {
    /** A file of [size] bytes: [lines] for text too large to merge, [image] for an image small enough to show. */
    data class File(val size: Long, val lines: Long? = null, val image: PreviewImage? = null) : VersionPreview

    /** A symbolic link to [target]. [resolves]: it points to something that exists. */
    data class Link(val target: String, val resolves: Boolean) : VersionPreview

    /** A submodule at [commit]: its [summary] from the submodule's own repository, or null when that doesn't have it. */
    data class Commit(val commit: String, val summary: CommitSummary?) : VersionPreview

    /**
     * A Git LFS pointer to [obj]. [downloaded]: the object is in the repository's LFS store, and [image] is it, when
     * it's an image small enough to show.
     */
    data class Lfs(val obj: LfsObject, val downloaded: Boolean, val image: PreviewImage? = null) : VersionPreview
}

/** The image types that the cards show, by file extension: those that Compose's decoder reads. */
val PREVIEW_IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")

/** Whether [path]'s extension is one of [PREVIEW_IMAGE_TYPES]. */
fun isPreviewImage(path: String): Boolean = imageType(path) in PREVIEW_IMAGE_TYPES

/** [path]'s extension, in lower case, or "" without one. */
fun imageType(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

/** The bytes of an image, of a type that Compose decodes ([PREVIEW_IMAGE_TYPES]). Equal by content. */
class PreviewImage(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is PreviewImage && bytes.contentEquals(other.bytes)

    override fun hashCode() = bytes.contentHashCode()

    override fun toString() = "PreviewImage(${bytes.size} bytes)"
}

/** A commit's first line, author and time, to name a submodule's commit. */
data class CommitSummary(val subject: String, val author: String, val timeMillis: Long)

/** The diff of the version kept against the base: [added] and [removed] lines, and the first [lines] of it. */
data class KeptChanges(
    val added: Int,
    val removed: Int,
    val lines: List<PreviewLine>,
    /** How many lines of the diff [lines] leaves out. */
    val more: Int,
)

/** A line of a diff shown in a card: a hunk's header, or a line kept, added or removed. */
data class PreviewLine(val type: PreviewLineType, val text: String)

enum class PreviewLineType { HUNK, CONTEXT, ADDED, REMOVED }

/**
 * A submodule's two commits: [relation] when the submodule's repository has both, and [checkedOut], the commit that it
 * has checked out. [initialized]: the submodule has a repository at all.
 */
data class SubmodulePreview(val initialized: Boolean, val relation: CommitRelation?, val checkedOut: String?)

enum class CommitRelation {
    /** Ours has theirs in its history. */
    OURS_CONTAINS_THEIRS,

    /** Theirs has ours in its history. */
    THEIRS_CONTAINS_OURS,

    /** Neither has the other. */
    DIVERGED,
}
