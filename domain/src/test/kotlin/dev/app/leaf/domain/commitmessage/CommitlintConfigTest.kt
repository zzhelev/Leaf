// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun always(vararg values: String) = EnumRule(values.toSet(), allows = true)

/** Reading a repository's commitlint config, in each of the forms that commitlint reads (fork-only). */
class CommitlintConfigTest {
    @Test
    fun `reads JSON`() {
        val rules = parseCommitlintConfig(
            ".commitlintrc.json",
            """{"extends": ["@commitlint/config-conventional"], "rules": {"type-enum": [2, "always", ["feat", "fix", "deps"]],
               "scope-enum": [1, "always", ["clone", "log"]]}}""",
        )!!

        assertEquals(always("feat", "fix", "deps"), rules.types)
        assertEquals(always("clone", "log"), rules.scopes)
        assertEquals(".commitlintrc.json", rules.file)
    }

    @Test
    fun `reads YAML's flow style, bare and quoted`() {
        val rules = parseCommitlintConfig(
            ".commitlintrc.yml",
            "extends:\n  - \"@commitlint/config-conventional\"\nrules:\n  type-enum: [2, always, [feat, 'fix', \"security\"]]\n",
        )!!

        assertEquals(always("feat", "fix", "security"), rules.types)
        assertNull(rules.scopes)
    }

    @Test
    fun `reads JavaScript and TypeScript, comments in the list included`() {
        val js = parseCommitlintConfig(
            "commitlint.config.js",
            """
            module.exports = {
              extends: ['@commitlint/config-conventional'],
              rules: {
                'type-enum': [2, 'always', [
                  'feat', 'fix', // the usual ones
                  /* ours */ 'deps',
                ]],
              },
            };
            """.trimIndent(),
        )!!
        val ts = parseCommitlintConfig(
            "commitlint.config.ts",
            "export default { rules: { \"scope-enum\": [2, \"always\", [`clone`, `log`]] } } satisfies UserConfig;",
        )!!

        assertEquals(always("feat", "fix", "deps"), js.types)
        assertEquals(always("clone", "log"), ts.scopes)
        assertNull(ts.types)
    }

    @Test
    fun `without a type rule, the preset's types`() {
        assertEquals(
            always(*STANDARD_COMMIT_TYPES.toTypedArray()),
            parseCommitlintConfig(".commitlintrc", "extends: ['@commitlint/config-conventional']")!!.types,
        )
        assertFalse(
            parseCommitlintConfig(".commitlintrc", "{\"extends\": [\"@commitlint/config-angular\"]}")!!.allowsType("chore"),
            "The Angular preset has no chore",
        )
        assertNull(parseCommitlintConfig(".commitlintrc", "{}")!!.types, "No preset, no rule: any type")
    }

    @Test
    fun `a rule turned off, or one that isn't a literal list, allows any type, whatever the preset says`() {
        val off = "extends: ['@commitlint/config-conventional']\nrules:\n  type-enum: [0, always, [feat]]"
        val built = "module.exports = { extends: ['@commitlint/config-conventional'], rules: { 'type-enum': [2, 'always', [...types]] } }"
        val block = "extends: ['@commitlint/config-conventional']\nrules:\n  type-enum:\n    - 2\n    - always\n    - - feat\n"

        val variable = "module.exports = { rules: { 'type-enum': [2, 'always', [TYPES]] } }"

        for ((file, config) in listOf(
            ".commitlintrc.yml" to off,
            "commitlint.config.js" to built,
            ".commitlintrc.yml" to block,
            "commitlint.config.js" to variable,
        )) {
            assertNull(parseCommitlintConfig(file, config)!!.types, config)
        }
    }

    @Test
    fun `never leaves its values out`() {
        val rules = parseCommitlintConfig(".commitlintrc.json", """{"rules": {"type-enum": [2, "never", ["wip"]]}}""")!!

        assertFalse(rules.allowsType("wip"))
        assertTrue(rules.allowsType("feat"))
    }

    @Test
    fun `package json counts only with a commitlint key`() {
        val rules = parseCommitlintConfig(
            "package.json",
            """{"name": "app", "commitlint": {"rules": {"type-enum": [2, "always", ["feat"]]}}}""",
        )!!

        assertEquals(always("feat"), rules.types)
        assertNull(parseCommitlintConfig("package.json", """{"name": "app", "scripts": {"type-enum": "x"}}"""))
        assertNull(parseCommitlintConfig("package.json", "not json"))
    }
}
