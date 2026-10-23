// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.highlighting

/**
 * A language that Leaf colours, and the TextMate grammar that colours it.
 *
 * [name] is the grammar's name in `textmate/GRAMMARS.md`. File names, extensions and interpreters are in lower case.
 */
data class Language(
    val name: String,
    val scopeName: String,
    /** Whole file names, such as `makefile`. */
    val fileNames: Set<String> = emptySet(),
    /** Starts of file names, such as `dockerfile.` for `Dockerfile.dev`. */
    val fileNamePrefixes: Set<String> = emptySet(),
    /** Extensions without their first dot, such as `kt` or `gradle.kts`. */
    val extensions: Set<String> = emptySet(),
    /** What a first line's `#!` runs, without a version: `python` for `#!/usr/bin/env python3`. */
    val interpreters: Set<String> = emptySet(),
)

/** The languages that Leaf colours, and how a file's language is found (`docs/fork/syntax-highlighting.md`, 4.1). */
object Languages {

    val all: List<Language> = listOf(
        Language(
            "kotlin", "source.kotlin",
            extensions = setOf("kt", "kts", "gradle.kts"),
            interpreters = setOf("kotlin"),
        ),
        Language("java", "source.java", extensions = setOf("java", "jav")),
        Language(
            "groovy", "source.groovy",
            fileNames = setOf("jenkinsfile"),
            extensions = setOf("groovy", "gvy", "gy", "gsh", "gradle"),
            interpreters = setOf("groovy"),
        ),
        Language("scala", "source.scala", extensions = setOf("scala", "sc", "sbt"), interpreters = setOf("scala")),
        Language("swift", "source.swift", extensions = setOf("swift"), interpreters = setOf("swift")),
        Language("objective-c", "source.objc", extensions = setOf("m")),
        Language("objective-cpp", "source.objcpp", extensions = setOf("mm")),
        Language("c", "source.c", extensions = setOf("c", "i")),
        // `.h` is C++, as in VS Code: the C++ grammar colours C too.
        Language(
            "cpp", "source.cpp",
            extensions = setOf(
                "cpp", "cc", "cxx", "c++", "cppm", "ixx", "hpp", "hh", "hxx", "h++", "h", "inl", "ipp", "tpp", "txx",
                "ino",
            ),
        ),
        Language("csharp", "source.cs", extensions = setOf("cs", "csx", "cake")),
        Language("go", "source.go", extensions = setOf("go")),
        Language("rust", "source.rust", extensions = setOf("rs")),
        Language("dart", "source.dart", extensions = setOf("dart")),
        Language(
            "python", "source.python",
            fileNames = setOf("sconstruct", "sconscript"),
            extensions = setOf("py", "pyw", "pyi", "gyp", "gypi", "rpy"),
            interpreters = setOf("python", "pypy"),
        ),
        Language(
            "ruby", "source.ruby",
            fileNames = setOf(
                "gemfile", "rakefile", "podfile", "vagrantfile", "brewfile", "guardfile", "capfile", "fastfile",
                "appfile", "dangerfile", "berksfile",
            ),
            extensions = setOf("rb", "rbw", "rake", "gemspec", "ru", "podspec", "rbi", "jbuilder", "thor"),
            interpreters = setOf("ruby"),
        ),
        Language("perl", "source.perl", extensions = setOf("pl", "pm", "pod", "psgi"), interpreters = setOf("perl")),
        Language(
            "php", "source.php",
            extensions = setOf("php", "phtml", "php3", "php4", "php5", "phpt"),
            interpreters = setOf("php"),
        ),
        Language("lua", "source.lua", extensions = setOf("lua"), interpreters = setOf("lua")),
        Language(
            "r", "source.r",
            fileNames = setOf(".rprofile"),
            extensions = setOf("r"),
            interpreters = setOf("rscript"),
        ),
        Language("julia", "source.julia", extensions = setOf("jl"), interpreters = setOf("julia")),
        Language("haskell", "source.haskell", extensions = setOf("hs", "hs-boot"), interpreters = setOf("runghc")),
        Language(
            "erlang", "source.erlang",
            fileNames = setOf("rebar.config"),
            extensions = setOf("erl", "hrl", "escript", "app.src"),
            interpreters = setOf("escript"),
        ),
        Language("elixir", "source.elixir", extensions = setOf("ex", "exs"), interpreters = setOf("elixir")),
        Language(
            "clojure", "source.clojure",
            extensions = setOf("clj", "cljs", "cljc", "edn", "bb"),
            interpreters = setOf("bb"),
        ),
        Language("zig", "source.zig", extensions = setOf("zig", "zon")),
        Language(
            "javascript", "source.js",
            fileNames = setOf("jakefile"),
            extensions = setOf("js", "mjs", "cjs", "es6"),
            interpreters = setOf("node", "nodejs"),
        ),
        Language(
            "typescript", "source.ts",
            extensions = setOf("ts", "mts", "cts", "d.ts"),
            interpreters = setOf("ts-node"),
        ),
        Language("jsx", "source.js.jsx", extensions = setOf("jsx")),
        Language("tsx", "source.tsx", extensions = setOf("tsx")),
        Language("vue", "text.html.vue", extensions = setOf("vue")),
        Language("svelte", "source.svelte", extensions = setOf("svelte")),
        Language("html", "text.html.basic", extensions = setOf("html", "htm", "xhtml", "shtml", "xht")),
        Language("css", "source.css", extensions = setOf("css")),
        Language("scss", "source.css.scss", extensions = setOf("scss")),
        Language("less", "source.css.less", extensions = setOf("less")),
        Language(
            "json", "source.json",
            fileNames = setOf("composer.lock", ".watchmanconfig"),
            extensions = setOf("json", "geojson", "topojson", "webmanifest", "har", "ipynb"),
        ),
        Language(
            "jsonc", "source.json.comments",
            fileNames = setOf(
                "tsconfig.json", "jsconfig.json", ".eslintrc", ".eslintrc.json", ".babelrc", ".babelrc.json",
                "babel.config.json", ".jshintrc", ".swcrc", "devcontainer.json", ".devcontainer.json", "typedoc.json",
            ),
            extensions = setOf("jsonc", "code-workspace", "code-snippets"),
        ),
        Language("json5", "source.json5", extensions = setOf("json5")),
        Language("jsonl", "source.json.lines", extensions = setOf("jsonl", "ndjson")),
        Language(
            "xml", "text.xml",
            fileNames = setOf("web.config", "app.config"),
            extensions = setOf(
                "xml", "xsd", "plist", "svg", "csproj", "fsproj", "vbproj", "vcxproj", "props", "targets", "resx",
                "xaml", "nuspec", "iml", "storyboard", "xib", "wsdl", "rss", "atom", "kml", "gpx", "xlf", "xliff",
                "xul", "tld",
            ),
        ),
        Language("xsl", "text.xml.xsl", extensions = setOf("xsl", "xslt")),
        Language(
            "yaml", "source.yaml",
            fileNames = setOf(".clang-format", ".clang-tidy", ".gemrc"),
            extensions = setOf("yaml", "yml", "cff"),
        ),
        Language(
            "toml", "source.toml",
            fileNames = setOf("cargo.lock", "pipfile", "poetry.lock", "uv.lock", "gopkg.lock"),
            extensions = setOf("toml"),
        ),
        Language(
            "ini", "source.ini",
            fileNames = setOf(
                ".editorconfig", ".gitconfig", ".gitmodules", ".gitattributes", ".npmrc", ".pypirc", ".flake8",
                ".coveragerc", ".pylintrc",
            ),
            extensions = setOf("ini", "cfg", "conf", "properties", "prefs"),
        ),
        Language(
            "markdown", "text.html.markdown",
            extensions = setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdwn", "mkdown", "ronn"),
        ),
        Language(
            "latex", "text.tex.latex",
            extensions = setOf("tex", "latex", "ltx", "sty", "cls", "dtx", "ins", "bbx", "cbx"),
        ),
        Language("bibtex", "text.bibtex", extensions = setOf("bib")),
        Language("sql", "source.sql", extensions = setOf("sql", "ddl", "pgsql", "psql")),
        Language("graphql", "source.graphql", extensions = setOf("graphql", "gql", "graphqls")),
        Language("proto", "source.proto", extensions = setOf("proto")),
        Language(
            "shellscript", "source.shell",
            fileNames = setOf(
                ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".bash_aliases", ".profile", ".zshrc",
                ".zprofile", ".zshenv", ".zlogin", ".zlogout", ".envrc", "pkgbuild", "apkbuild",
            ),
            extensions = setOf("sh", "bash", "zsh", "ksh", "ash", "bats", "command", "ebuild", "eclass"),
            interpreters = setOf("sh", "bash", "zsh", "ksh", "dash", "ash"),
        ),
        Language(
            "powershell", "source.powershell",
            extensions = setOf("ps1", "psm1", "psd1", "pssc", "psrc"),
            interpreters = setOf("pwsh", "powershell"),
        ),
        Language("bat", "source.batchfile", extensions = setOf("bat", "cmd")),
        Language(
            "docker", "source.dockerfile",
            fileNames = setOf("dockerfile", "containerfile"),
            fileNamePrefixes = setOf("dockerfile.", "containerfile."),
            extensions = setOf("dockerfile", "containerfile"),
        ),
        Language(
            "make", "source.makefile",
            fileNames = setOf("makefile", "gnumakefile", "bsdmakefile"),
            extensions = setOf("mk", "mak", "make", "mkfile"),
            interpreters = setOf("make"),
        ),
        Language("cmake", "source.cmake", fileNames = setOf("cmakelists.txt"), extensions = setOf("cmake")),
        Language(
            "hcl", "source.hcl",
            fileNames = setOf(".terraformrc", "terraform.rc"),
            extensions = setOf("hcl", "nomad"),
        ),
        Language("terraform", "source.hcl.terraform", extensions = setOf("tf", "tfvars", "tftest.hcl")),
        Language("diff", "source.diff", extensions = setOf("diff", "patch", "rej")),
        Language(
            "git-commit", "text.git-commit",
            fileNames = setOf("commit_editmsg", "merge_msg", "tag_editmsg", "squash_msg", "notes_editmsg"),
        ),
        Language("git-rebase", "text.git-rebase", fileNames = setOf("git-rebase-todo")),
        // Grammars that Leaf ships because others embed them, which colour files of their own too.
        Language(
            "coffee", "source.coffee",
            fileNames = setOf("cakefile"),
            extensions = setOf("coffee", "cson", "iced"),
        ),
        Language("fsharp", "source.fsharp", extensions = setOf("fs", "fsi", "fsx", "fsscript")),
        Language("handlebars", "text.html.handlebars", extensions = setOf("hbs", "handlebars")),
        Language("haml", "text.haml", extensions = setOf("haml")),
        Language("pug", "text.pug", extensions = setOf("pug", "jade")),
        Language(
            "raku", "source.perl.6",
            extensions = setOf("raku", "rakumod", "rakutest", "p6", "pl6", "pm6"),
            interpreters = setOf("raku"),
        ),
        Language("rst", "source.rst", extensions = setOf("rst")),
        Language("vb", "source.asp.vb.net", extensions = setOf("vb", "bas")),
        Language("sass", "source.sass", extensions = setOf("sass")),
        Language("stylus", "source.stylus", extensions = setOf("styl", "stylus")),
        Language("postcss", "source.css.postcss", extensions = setOf("pcss", "postcss")),
        Language("log", "text.log", extensions = setOf("log")),
        Language("abap", "source.abap", extensions = setOf("abap")),
    )

    private val byFileName = index { it.fileNames }
    private val byExtension = index { it.extensions }
    private val byInterpreter = index { it.interpreters }

    /**
     * The language of the file at [path], from its name, then its extensions, the longest first (`.gradle.kts` before
     * `.kts`), then what its [firstLine]'s `#!` runs; or null, and the file stays plain.
     */
    fun of(path: String, firstLine: String? = null): Language? {
        val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name.isEmpty()) return null
        byFileName[name]?.let { return it }
        all.firstOrNull { language -> language.fileNamePrefixes.any { name.startsWith(it) } }?.let { return it }
        var dot = name.indexOf('.', startIndex = 1)
        while (dot >= 0) {
            byExtension[name.substring(dot + 1)]?.let { return it }
            dot = name.indexOf('.', startIndex = dot + 1)
        }
        return firstLine?.let(::interpreterOf)?.let { byInterpreter[it] }
    }

    /** What a `#!` line runs, in lower case and without a version: `python` for `#!/usr/bin/env python3.12 -u`. */
    fun interpreterOf(firstLine: String): String? {
        if (!firstLine.startsWith("#!")) return null
        val words = firstLine.substring(2).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val program = words.firstOrNull()?.substringAfterLast('/') ?: return null
        val command = if (program == "env") {
            // `env -S node --flag`, `env VAR=1 ruby`: the first word that is neither an option nor a variable.
            words.drop(1).firstOrNull { !it.startsWith("-") && '=' !in it }?.substringAfterLast('/') ?: return null
        } else {
            program
        }
        return command.lowercase().trimEnd { it.isDigit() || it == '.' }.ifEmpty { null }
    }

    private fun index(keys: (Language) -> Set<String>): Map<String, Language> =
        all.flatMap { language -> keys(language).map { it to language } }.toMap()

    private val WHITESPACE = Regex("""\s+""")
}
