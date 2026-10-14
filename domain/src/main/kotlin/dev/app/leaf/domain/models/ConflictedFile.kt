// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * A file with conflicts as the resolver loads it (`docs/fork/conflict-resolver.md`, 2.4): its versions in the index,
 * which branch or commit ours and theirs are, and what the resolver can do with it.
 */
data class ConflictedFile(
    /**
     * Relative to the working tree, with `/`. For a file renamed differently on each side ([ConflictContent.Renamed]),
     * its old name, whichever of its paths was opened.
     */
    val path: String,
    val base: ConflictStage?,
    val ours: ConflictStage?,
    val theirs: ConflictStage?,

    /** What ours and theirs have at the path: a file, a symbolic link, a submodule, or nothing. */
    val conflict: FileConflict,

    /** Which branch or commit ours and theirs are, or null when the repository's state doesn't tell. */
    val sides: ConflictSides?,
    val content: ConflictContent,

    /** What the choice cards show, for a conflict that three panes can't show; null for text. */
    val preview: ConflictPreview? = null,
) {
    val stages: ConflictStages = ConflictStages.of(base != null, ours != null, theirs != null)
}

/** One version of a conflicted file in the index: stage 1 (the base), 2 (ours) or 3 (theirs). */
data class ConflictStage(
    /** The blob's id, or the commit's for a submodule. */
    val objectId: String,

    /**
     * Its file mode, as JGit's `FileMode.bits`: in octal, `100644` for a file, `100755` for an executable one, `120000`
     * for a symbolic link and `160000` for a submodule.
     */
    val mode: Int,
)

/** Which versions of a conflicted path the index has, named as `git status` names them. */
enum class ConflictStages {
    /** UU: the base, ours and theirs. */
    BOTH_MODIFIED,

    /** AA: ours and theirs, without a base. */
    BOTH_ADDED,

    /** DU: the base and theirs. */
    DELETED_BY_US,

    /** UD: the base and ours. A file that ours renamed and theirs deleted looks the same, at ours' path. */
    DELETED_BY_THEM,

    /**
     * DD: the base alone. With [ADDED_BY_US] and [ADDED_BY_THEM], what git leaves when each side renamed the file
     * differently: each version at its own path.
     */
    BOTH_DELETED,

    /** AU: ours alone. */
    ADDED_BY_US,

    /** UA: theirs alone. */
    ADDED_BY_THEM;

    companion object {
        fun of(base: Boolean, ours: Boolean, theirs: Boolean): ConflictStages = when {
            base && ours && theirs -> BOTH_MODIFIED
            ours && theirs -> BOTH_ADDED
            base && theirs -> DELETED_BY_US
            base && ours -> DELETED_BY_THEM
            base -> BOTH_DELETED
            ours -> ADDED_BY_US
            theirs -> ADDED_BY_THEM
            else -> throw IllegalArgumentException("A conflict without any version")
        }
    }
}

/** What the resolver can show for a conflicted file, and how it's resolved (`conflict-resolver.md`, 2.4). */
sealed interface ConflictContent {
    /**
     * Text on both sides: the three panes, from the merge of the three versions. The base is empty when both added
     * the file.
     */
    data class Text(val merge: TextMerge, val workingFile: WorkingFileCheck) : ConflictContent

    /**
     * Each version is at its own path ([ConflictStages.BOTH_DELETED], [ConflictStages.ADDED_BY_US],
     * [ConflictStages.ADDED_BY_THEM]), and no [RenamedFile] takes this one in: a file against a folder that git moved
     * to `name~branch`, say. A card per side: the side with a file keeps it here, the other leaves it out.
     */
    data object OtherPaths : ConflictContent

    /**
     * A file that each side renamed differently. git merged both sides' changes to its content into both new names, so
     * the name is left to pick. [contentConflicts]: that merge stopped at conflicts, so the new names hold conflict
     * markers; picking a name then makes it a conflict of its content, between [theirsCommit]'s version and HEAD's, which
     * the three panes show. Null when the operation doesn't tell which commit theirs is (a revert, a stash).
     */
    data class Renamed(
        val file: RenamedFile,
        val contentConflicts: Boolean,
        val theirsCommit: String?,
    ) : ConflictContent

    /** A submodule on either side: a choice of commit, the stages' ids. */
    data object Submodule : ConflictContent

    /** One side deleted the file and the other kept it ([ConflictStages.DELETED_BY_US] or `DELETED_BY_THEM`). */
    data object Deleted : ConflictContent

    /** A symbolic link on either side: a choice of target. A side with a file has no target. */
    data class SymbolicLink(val oursTarget: String?, val theirsTarget: String?) : ConflictContent

    /** Git LFS pointers: their objects, or null for a side whose file isn't a pointer. */
    data class Lfs(val ours: LfsObject?, val theirs: LfsObject?) : ConflictContent

    /** A binary file, or one that the `merge` attribute merges as binary: a choice of side. */
    data object Binary : ConflictContent

    /** Text larger than [limit] bytes on a side ([size], the largest): a choice of side, or the external tool. */
    data class TooLarge(val size: Long, val limit: Long) : ConflictContent
}

/** The object that a Git LFS pointer names: its SHA-256 and its size in bytes. */
data class LfsObject(val oid: String, val size: Long)

/**
 * The working file next to the merge (`conflict-resolver.md`, 2.2), when the conflict was loaded: its [state], its
 * [conflicts] as git rerere counts them, and the id that git would give its bytes ([contentId], null without a
 * file), so that Save can tell whether something changed it since.
 */
data class WorkingFileCheck(val state: WorkingFileState, val conflicts: Int, val contentId: String?)

enum class WorkingFileState {
    /** As many conflicts as the merge: the usual case. */
    MATCHES,

    /** No conflict markers: resolved by hand, by an agent or by `git rerere`. */
    NO_MARKERS,

    /** Markers, but fewer or more conflicts than the merge, or markers that don't pair up. */
    DIFFERENT,

    /** No file at the path, or a folder or a symbolic link in its place. */
    MISSING,
}

/**
 * A file that each side renamed differently, as git leaves it: [original] at stage 1 alone (both deleted), [ours] at
 * stage 2 alone (added by us), [theirs] at stage 3 alone (added by them). git doesn't record that the three belong
 * together; [ours] and [theirs] hold the same merged content, which pairs them.
 */
data class RenamedFile(val original: String, val ours: String, val theirs: String) {
    val paths: List<String> get() = listOf(original, ours, theirs)

    fun path(side: ConflictSide) = if (side == ConflictSide.OURS) ours else theirs
}
