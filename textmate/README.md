# textmate

The engine that colours code in Leaf: it reads TextMate grammars, the format of VS Code, GitHub and shiki, and splits
each line into tokens with their scopes. It is a copy of the `core` module of
[kotlin-textmate](https://github.com/ivan-magda/kotlin-textmate), a Kotlin port of
[vscode-textmate](https://github.com/microsoft/vscode-textmate), kept in Leaf so that Leaf can fix it. Regular
expressions run on Joni, the Java port of Oniguruma, so there is no native code. Why this engine is in
`docs/fork/syntax-highlighting.md`.

| | |
|---|---|
| Copied from | kotlin-textmate 0.3.0, commit `a500bc7a`, `core/src/` |
| Which ports | vscode-textmate 9.3.2, commit `25b68dad` |
| Package | `dev.textmate`, as in kotlin-textmate |
| Licence | MIT, as kotlin-textmate ([LICENSE](LICENSE)); vscode-textmate's notice in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) |

## How the copy differs

The commit that added this folder has kotlin-textmate's sources as published. Only these differ, so that it builds
in Leaf:

- the tests run on JUnit 5, as Leaf's do, where kotlin-textmate's run on JUnit 4: imports, `@BeforeEach`, the message as
  the last argument of an assertion, and the two parameterized tests;
- the build file is Leaf's, on its `buildsrc` convention;
- `TextMateGrammar.VERSION`, which kotlin-textmate's build writes from its version, and its test are left out: this
  file records the version;
- the golden script (`tools/generate-golden/`) finds the grammars and writes the goldens in this module's folders.

Each change after that is a commit of its own:

- the theme package (`dev.textmate.theme`) is left out, with its tests and themes: Leaf maps scopes to its own colours;
- grammars are read with kotlinx.serialization, which Leaf already uses, in place of Gson, and as leniently as
  vscode-textmate reads them: an entry of the wrong type is left out, where Gson refused the whole grammar. Five of
  shiki's grammars have such entries (XML's captures have strings among the rules), and 33 failed to load with the
  grammars that embed them;
- `Grammar.tokenizeLine` takes vscode-textmate's `timeLimit`, in milliseconds, and says when a line went past it
  (`TokenizeLineResult.stoppedEarly`). Like vscode-textmate, it checks the time before each match, so one regular
  expression that takes long isn't stopped;
- look-behinds that Joni refuses are rewritten into ones that it takes and that match the same text
  (`regex/LookbehindRewrite.kt`). Joni follows an older Oniguruma than VS Code's, and takes alternatives of different
  lengths only at a look-behind's top level: `(?<=(?:^|[^\\])\n)` becomes `(?<=^\n|[^\\]\n)`. Before, a rule with such
  a look-behind never matched: shell's rule for a command, so that most lines of a script stayed plain, CSS's
  selectors, Rust's paths, what follows `import` in JavaScript and TypeScript, line continuations in C and C++. It
  fixes 173 of the 351 patterns that Joni refused in shiki's 260 grammars, and the files compared below now match
  VS Code token for token.

## Differences from VS Code that remain

Measured on 2026-10-23 against vscode-textmate 9.3.2, the engine VS Code runs, with shiki's grammars 1.32.22, on a
Dockerfile, a Rust file, 28,502 lines of JSON, 3,705 lines of Kotlin, the 4,222 lines of Leaf's `CLAUDE.md`, a TOML
file, a minified JavaScript file, Java, a shell script, a workflow's YAML, 1,906 lines of Python, 1,758 lines of XML
and a CSS file: every token the same, but one in Python, in a regular expression's character set. In the grammars of
the languages Leaf colours, these patterns still never match, where VS Code matches them:

- a look-behind with an unbounded repeat, which no rewrite can spread: C#'s `await` after `.` (`(?<!\.\s*)`), Julia's
  `as`, Ruby's block parameters after `{ ` or `do `, and `await using` in JavaScript and TypeScript;
- an interval written backwards, `\s+{1,0}`, which Oniguruma 6.9 reads as a possessive `{0,1}` and Joni refuses: the
  start of a C or C++ documentation comment (`///`, `/**`) after spaces.

## Taking a later fix from kotlin-textmate

Compare kotlin-textmate's `core/src/` from `a500bc7a` to the commit with the fix, and apply that to `src/`. The commits
that changed this folder since it was added say what Leaf changed, so a conflict can be told from a fix that Leaf
already has. Then update the table above.

## Tests

```bash
./gradlew :textmate:test
```

- `FirstMateConformanceTest` runs first-mate's fixtures, which vscode-textmate tests itself against.
- `GoldenSnapshotTest` compares the tokens of the files in `tools/generate-golden/corpus/` with goldens that
  vscode-textmate made: kotlin-textmate's (JSON, Kotlin, Markdown, JavaScript) and Leaf's (shell, CSS and Rust,
  which need the look-behind rewrite). To make them again (Node.js 21.2 or newer):

  ```bash
  cd textmate/tools/generate-golden && npm install && npm run generate
  ```
