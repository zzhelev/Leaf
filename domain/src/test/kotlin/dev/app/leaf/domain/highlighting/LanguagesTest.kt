// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguagesTest {

    private fun name(path: String, firstLine: String? = null) = Languages.of(path, firstLine)?.name

    @Test
    fun `whole file names come first`() {
        assertEquals("docker", name("Dockerfile"))
        assertEquals("docker", name("Containerfile"))
        assertEquals("make", name("Makefile"))
        assertEquals("make", name("GNUmakefile"))
        assertEquals("cmake", name("CMakeLists.txt"))
        assertEquals("groovy", name("Jenkinsfile"))
        assertEquals("ruby", name("Gemfile"))
        assertEquals("shellscript", name(".bashrc"))
        assertEquals("shellscript", name(".zshrc"))
        assertEquals("ini", name(".editorconfig"))
        assertEquals("git-commit", name("COMMIT_EDITMSG"))
        assertEquals("git-rebase", name("git-rebase-todo"))
        // The name before the extension: tsconfig.json has comments, which plain JSON doesn't allow.
        assertEquals("jsonc", name("tsconfig.json"))
        assertEquals("toml", name("Cargo.lock"))
    }

    @Test
    fun `a name's start, for Dockerfiles of each environment`() {
        assertEquals("docker", name("Dockerfile.dev"))
        assertEquals("docker", name("docker/Containerfile.prod"))
        assertEquals("docker", name("app.Dockerfile"))
    }

    @Test
    fun `extensions, the longest first`() {
        assertEquals("kotlin", name("Main.kt"))
        assertEquals("kotlin", name("build.gradle.kts"))
        assertEquals("groovy", name("build.gradle"))
        assertEquals("typescript", name("index.d.ts"))
        assertEquals("terraform", name("main.tf"))
        assertEquals("terraform", name("checks.tftest.hcl"))
        assertEquals("hcl", name("job.hcl"))
        assertEquals("cpp", name("graph.h"))
        assertEquals("objective-c", name("View.m"))
        assertEquals("json", name("tsconfig.base.json"))
        assertEquals("jsonc", name(".eslintrc.json"))
        assertEquals("xml", name("res/values/strings.xml"))
        assertEquals("bibtex", name("refs.bib"))
    }

    @Test
    fun `case doesn't matter, and paths are read to their last name`() {
        assertEquals("markdown", name("README.MD"))
        assertEquals("python", name("Script.PY"))
        assertEquals("make", name("makefile"))
        assertEquals("docker", name("DOCKERFILE"))
        assertEquals("kotlin", name("app/src/main/kotlin/Main.kt"))
        assertEquals("python", name("""C:\repo\tools\sync.py"""))
    }

    @Test
    fun `a first line's #! when nothing else names the language`() {
        assertEquals("python", name("sync", "#!/usr/bin/env python3"))
        assertEquals("python", name("sync", "#! /usr/local/bin/python3.12 -u"))
        assertEquals("shellscript", name("deploy", "#!/bin/bash -euo pipefail"))
        assertEquals("shellscript", name("deploy", "#!/bin/sh"))
        assertEquals("javascript", name("cli", "#!/usr/bin/env -S node --no-warnings"))
        assertEquals("ruby", name("tool", "#!/usr/bin/env RUBYOPT=-W0 ruby"))
        assertEquals("perl", name("count", "#!/usr/bin/perl -w"))
        assertEquals("powershell", name("build", "#!/usr/bin/env pwsh"))
        // The name or extension wins over the line.
        assertEquals("python", name("sync.py", "#!/bin/bash"))
        assertEquals("make", name("Makefile", "#!/usr/bin/env python3"))
    }

    @Test
    fun `interpreters are read without their version or options`() {
        assertEquals("python", Languages.interpreterOf("#!/usr/bin/env python3.11"))
        assertEquals("lua", Languages.interpreterOf("#!/usr/bin/lua5.4"))
        assertEquals("node", Languages.interpreterOf("#!/usr/bin/env -S node --flag"))
        assertNull(Languages.interpreterOf("#!/usr/bin/env"))
        assertNull(Languages.interpreterOf("# not a shebang"))
        assertNull(Languages.interpreterOf("#!"))
    }

    @Test
    fun `files that no language names stay plain`() {
        assertNull(name("LICENSE"))
        assertNull(name("photo.jpg"))
        assertNull(name("notes"))
        assertNull(name("notes", "plain text"))
        assertNull(name("tool", "#!/usr/bin/env unknown-interpreter"))
        assertNull(name(""))
        assertNull(name("folder/"))
        assertNull(name(".gitignore"))
    }

    @Test
    fun `the table names each name, extension and interpreter once, in lower case`() {
        for (keys in listOf<(Language) -> Set<String>>({ it.fileNames }, { it.extensions }, { it.interpreters })) {
            val all = Languages.all.flatMap(keys)
            assertEquals(all.size, all.toSet().size, "named twice: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}")
            assertTrue(all.all { it == it.lowercase() && it.isNotBlank() }, all.toString())
        }
        assertEquals(Languages.all.size, Languages.all.map { it.name }.toSet().size)
        assertEquals(Languages.all.size, Languages.all.map { it.scopeName }.toSet().size)
        // An interpreter is looked up without its version, so one ending in a digit would never be found.
        assertTrue(Languages.all.flatMap { it.interpreters }.none { it.last().isDigit() })
    }
}
