// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The files that commitlint reads its config from, in the order that it looks for them (cosmiconfig's search places,
 * commitlint 19): the first one there wins. `package.json` counts only with a `commitlint` key.
 */
val COMMITLINT_CONFIG_FILES = listOf(
    "package.json",
    ".commitlintrc",
    ".commitlintrc.json",
    ".commitlintrc.yaml",
    ".commitlintrc.yml",
    ".commitlintrc.js",
    ".commitlintrc.cjs",
    ".commitlintrc.mjs",
    ".commitlintrc.ts",
    ".commitlintrc.cts",
    "commitlint.config.js",
    "commitlint.config.cjs",
    "commitlint.config.mjs",
    "commitlint.config.ts",
    "commitlint.config.cts",
)

/** The types of the presets that a config can extend, for a config that sets no `type-enum` of its own. */
private val PRESET_TYPES = mapOf(
    "@commitlint/config-conventional" to STANDARD_COMMIT_TYPES,
    "@commitlint/config-angular" to listOf("build", "ci", "docs", "feat", "fix", "perf", "refactor", "revert", "style", "test"),
)

/**
 * A commitlint `*-enum` rule (fork-only): with [allows], only [values] pass (`always`); without, every value but those
 * (`never`).
 */
data class EnumRule(val values: Set<String>, val allows: Boolean) {
    fun passes(value: String): Boolean = (value in values) == allows
}

/**
 * What a repository's commitlint config says about Conventional Commits types and scopes (fork-only), from [file]: its
 * `type-enum` (or its preset's) and `scope-enum` rules, each null when the config sets none that Leaf can read. A
 * config with neither still tells that the repository uses Conventional Commits.
 */
data class CommitlintRules(val file: String, val types: EnumRule? = null, val scopes: EnumRule? = null) {
    fun allowsType(type: String): Boolean = types?.passes(type) ?: true

    fun allowsScope(scope: String): Boolean = scopes?.passes(scope) ?: true
}

/**
 * A rule as commitlint configs write it, in JSON, YAML's flow style or JavaScript: the rule's name as a key, then
 * `[level, 'always' or 'never', [values]]`.
 */
private fun enumRulePattern(name: String) =
    Regex("""["']?$name["']?\s*:\s*\[\s*([0-2])\s*,\s*["']?(always|never)["']?\s*,\s*\[([^\[\]]*)]""")

private val QUOTED_VALUE = Regex("""^(["'`])([^"'`]+)\1$""")
private val BARE_VALUE = Regex("""^[A-Za-z0-9][A-Za-z0-9_.\-/]*$""")

/** The files that may be YAML, where a list's values can go without quotes. Elsewhere a bare word is a variable. */
private val YAML_FILES = setOf(".commitlintrc", ".commitlintrc.yaml", ".commitlintrc.yml")
private val LIST_COMMENTS = Regex("""//[^\n]*|/\*.*?\*/|#[^\n]*""", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads [text], the config in [file], or null when it isn't a config: a `package.json` without a `commitlint` key, or
 * one that isn't JSON. commitlint runs JavaScript and TypeScript configs, and reads YAML; Leaf runs and parses neither,
 * and reads the rules' literal lists: a list that code builds, or a YAML rule in the block style, reads as no rule.
 */
fun parseCommitlintConfig(file: String, text: String): CommitlintRules? {
    val config = if (file == "package.json") packageJsonCommitlint(text) ?: return null else text

    return CommitlintRules(
        file = file,
        // The config's own rule, even turned off or unreadable, overrides its preset's
        types = when (val rule = enumRule(config, "type-enum", bareValues = file in YAML_FILES)) {
            RuleRead.Absent -> presetTypes(config)
            RuleRead.Off, RuleRead.Unreadable -> null
            is RuleRead.Read -> rule.rule
        },
        scopes = (enumRule(config, "scope-enum", bareValues = file in YAML_FILES) as? RuleRead.Read)?.rule,
    )
}

/** What [enumRule] found. */
private sealed interface RuleRead {
    /** The config doesn't name the rule. */
    data object Absent : RuleRead

    /** Level 0. */
    data object Off : RuleRead

    /** Named, but not as a literal list: code builds it, or YAML's block style lists it. */
    data object Unreadable : RuleRead

    data class Read(val rule: EnumRule) : RuleRead
}

/** The `commitlint` key of a `package.json`, as JSON text, or null. */
private fun packageJsonCommitlint(text: String): String? = try {
    (Json.parseToJsonElement(text) as? JsonObject)?.get("commitlint")?.toString()
} catch (e: IllegalArgumentException) {
    null
}

/** The rule [name] in [config]. With [bareValues], a value may go without quotes, as in YAML. */
private fun enumRule(config: String, name: String, bareValues: Boolean): RuleRead {
    val match = enumRulePattern(name).find(config)
        ?: return if (name in config) RuleRead.Unreadable else RuleRead.Absent
    val (level, applicable, list) = match.destructured

    if (level == "0") {
        return RuleRead.Off
    }

    val items = list.replace(LIST_COMMENTS, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val values = items.map { item ->
        QUOTED_VALUE.matchEntire(item)?.groupValues?.get(2)
            ?: item.takeIf { bareValues && BARE_VALUE.matches(it) }
            ?: return RuleRead.Unreadable
    }

    return if (values.isEmpty()) RuleRead.Unreadable else RuleRead.Read(EnumRule(values.toSet(), allows = applicable == "always"))
}

/** The types of the preset that [config] extends, or null. */
private fun presetTypes(config: String): EnumRule? = PRESET_TYPES.entries
    .firstOrNull { (preset, _) -> preset in config }
    ?.let { (_, types) -> EnumRule(types.toSet(), allows = true) }
