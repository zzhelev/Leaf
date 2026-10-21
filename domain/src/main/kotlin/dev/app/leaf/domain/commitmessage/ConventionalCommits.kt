// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import dev.app.leaf.domain.models.Commit

/** How many of the newest subjects decide whether a repository uses Conventional Commits. */
const val CONVENTIONAL_SAMPLE_SIZE = 50

/** At most this many types or scopes show in the suggestions at once; typing narrows them. */
const val MAX_COMMIT_TYPE_CHOICES = 8

/**
 * The usual Conventional Commits types, the ones of commitlint's conventional preset, the most common first. The app
 * says what each is for.
 */
val STANDARD_COMMIT_TYPES: List<String> =
    listOf("feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert")

/** A Conventional Commits subject's start: `type`, `type(scope)`, either with `!`, then `: ` and a description. */
private val CONVENTIONAL_SUBJECT = Regex("""^([A-Za-z][A-Za-z0-9-]*)(?:\(([^()\s]+)\))?!?: \S""")

/** When the commit field suggests Conventional Commits types (fork-only, Settings → Commits). */
enum class ConventionalCommitsMode {
    /** In a repository whose recent subjects use them ([ConventionalHistory.usesConventionalCommits]). */
    AUTOMATIC,

    /** In every repository. */
    ALWAYS,

    /** Never. */
    OFF;

    companion object {
        val DEFAULT = AUTOMATIC
    }

    /**
     * Whether the commit field suggests types in a repository with [history] and [rules]: always or never as the setting
     * says, or, when automatic, where the recent subjects use them or a commitlint config is set up.
     */
    fun suggestsIn(history: ConventionalHistory, rules: CommitlintRules?): Boolean = when (this) {
        OFF -> false
        ALWAYS -> true
        AUTOMATIC -> history.usesConventionalCommits || rules != null
    }
}

/**
 * What a repository's history says about its Conventional Commits (fork-only): how often each type and each scope of a
 * type was used in the commits that the log has loaded, and how many of the newest [sampled] subjects use the format.
 * Merge commits don't count: git writes their subjects.
 */
data class ConventionalHistory(
    val typeUses: Map<String, Int> = emptyMap(),
    val scopeUses: Map<String, Map<String, Int>> = emptyMap(),
    val conventionalSubjects: Int = 0,
    val sampled: Int = 0,
) {
    /** At least half of the newest subjects start with a type. */
    val usesConventionalCommits: Boolean get() = sampled > 0 && conventionalSubjects * 2 >= sampled
}

/** Reads [commits], which come newest first as the log has them. */
fun conventionalHistory(commits: Iterable<Commit>): ConventionalHistory {
    val typeUses = HashMap<String, Int>()
    val scopeUses = HashMap<String, HashMap<String, Int>>()
    var sampled = 0
    var conventional = 0

    for (commit in commits) {
        if (commit.parentsHashes.size > 1) {
            continue
        }

        val match = CONVENTIONAL_SUBJECT.find(commit.shortMessage)

        if (sampled < CONVENTIONAL_SAMPLE_SIZE) {
            sampled++

            if (match != null) {
                conventional++
            }
        }

        if (match != null) {
            val type = match.groupValues[1].lowercase()
            typeUses.merge(type, 1, Int::plus)

            match.groupValues[2].takeIf { it.isNotEmpty() }?.let { scope ->
                scopeUses.getOrPut(type) { HashMap() }.merge(scope, 1, Int::plus)
            }
        }
    }

    return ConventionalHistory(typeUses, scopeUses, conventional, sampled)
}

/**
 * A type or a scope to suggest, with how often the history used it. [isFromBranch] for the ticket key of the branch's
 * name, which comes first among the scopes.
 */
data class ConventionalChoice(val name: String, val uses: Int, val isFromBranch: Boolean = false)

/** What the caret is typing at the start of the subject, which the suggestions complete from the text's start to [end]. */
sealed interface ConventionalQuery {
    val end: Int

    /** The type: [typed] so far. */
    data class Type(val typed: String, override val end: Int) : ConventionalQuery

    /** A scope of [type], after its `(`: [typed] so far. */
    data class Scope(val type: String, val typed: String, override val end: Int) : ConventionalQuery
}

private val TYPING_TYPE = Regex("""[A-Za-z][A-Za-z0-9-]*""")
private val TYPING_SCOPE = Regex("""([A-Za-z][A-Za-z0-9-]*)\(([^()\s:]*)""")

/**
 * What the caret is typing at the start of the subject, the message's first line, or null: a type (at least a letter)
 * or, after the type's `(`, a scope. Only at the line's end, so that editing a subject that is there already suggests
 * nothing.
 */
fun conventionalQueryAt(text: String, caret: Int): ConventionalQuery? {
    val lineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }

    if (caret < 1 || caret != lineEnd) {
        return null
    }

    val typed = text.substring(0, caret)

    TYPING_TYPE.matchEntire(typed)?.let { return ConventionalQuery.Type(typed, caret) }
    TYPING_SCOPE.matchEntire(typed)?.let { match ->
        return ConventionalQuery.Scope(match.groupValues[1].lowercase(), match.groupValues[2], caret)
    }

    return null
}

/**
 * The types to suggest for what was [typed]: those that start with it, ignoring case. The standard ones, those the
 * history used and those a commitlint config allows, the most used first, then the standard ones in their order.
 * [rules] leave out the types that the repository's commitlint config doesn't allow.
 */
fun commitTypeChoices(
    typed: String,
    history: ConventionalHistory,
    rules: CommitlintRules? = null,
): List<ConventionalChoice> {
    val listed = rules?.types?.takeIf { it.allows }?.values.orEmpty()
    val names = (STANDARD_COMMIT_TYPES + history.typeUses.keys + listed).distinct()
    val standardOrder = STANDARD_COMMIT_TYPES.withIndex().associate { (index, name) -> name to index }

    return names
        .filter { it.startsWith(typed, ignoreCase = true) && (rules == null || rules.allowsType(it)) }
        .map { name -> ConventionalChoice(name, history.typeUses[name] ?: 0) }
        .sortedWith(
            compareByDescending<ConventionalChoice> { it.uses }
                .thenBy { standardOrder[it.name] ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
}

/**
 * The scopes to suggest for [type] and what was [typed]: those the history used with that type, the most used first, or
 * the history's scopes of any type when it used none with this one, and those a commitlint config allows. Those that
 * start with [typed], ignoring case, and that [rules] allow. Fork-only: the ticket key of the branch's name
 * ([branchKey], [ticketKeyIn]) comes first.
 */
fun commitScopeChoices(
    type: String,
    typed: String,
    history: ConventionalHistory,
    rules: CommitlintRules? = null,
    branchKey: String? = null,
): List<ConventionalChoice> {
    val uses = history.scopeUses[type]?.takeIf { it.isNotEmpty() }
        ?: history.scopeUses.values.fold(HashMap<String, Int>()) { all, scopes ->
            scopes.forEach { (scope, count) -> all.merge(scope, count, Int::plus) }
            all
        }
    val listed = rules?.scopes?.takeIf { it.allows }?.values.orEmpty()
    val names = (uses.keys + listed + listOfNotNull(branchKey)).distinct()

    return names
        .filter { it.startsWith(typed, ignoreCase = true) && (rules == null || rules.allowsScope(it)) }
        .map { ConventionalChoice(it, uses[it] ?: 0, isFromBranch = it == branchKey) }
        .sortedWith(
            compareByDescending<ConventionalChoice> { it.isFromBranch }
                .thenByDescending { it.uses }
                .thenBy { it.name }
        )
}

/**
 * [text] with [query] completed by [choice], and where the caret goes: a type becomes `type: `, a scope `type(scope): `,
 * both ready for the description.
 */
fun withConventionalChoice(text: String, query: ConventionalQuery, choice: ConventionalChoice): Pair<String, Int> {
    val completed = when (query) {
        is ConventionalQuery.Type -> "${choice.name}: "
        is ConventionalQuery.Scope -> "${query.type}(${choice.name}): "
    }

    return completed + text.substring(query.end) to completed.length
}
