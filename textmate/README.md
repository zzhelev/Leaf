# textmate

The engine that colours code in Leaf: it reads TextMate grammars, the format of VS Code, GitHub and shiki, and splits
each line into tokens with their scopes. It is a copy of the `core` module of
[kotlin-textmate](https://github.com/ivan-magda/kotlin-textmate), a Kotlin port of
[vscode-textmate](https://github.com/microsoft/vscode-textmate), kept in Leaf so that Leaf can fix it. Regular
expressions run on Joni, the Java port of Oniguruma, so there is no native code. Why this engine is in
`docs/fork/syntax-highlighting.md`.

The module also ships the grammars that Leaf colours with, 82 of shiki's, in `src/main/resources/grammars/`
([GRAMMARS.md](GRAMMARS.md) lists them and their licences), and Leaf's `ShippedGrammars` (`dev.app.leaf.textmate`)
loads them, with the grammars that inject into them.

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
  fixes 173 of the 351 patterns that Joni refused in shiki's 260 grammars;
- `\N` and `\O`, which Oniguruma 6.9 reads as any character but a newline and as any character, and Joni as the
  letters, are rewritten into what Joni reads the same way (`regex/EscapeRewrite.kt`). Python's, Less's and Stylus's
  grammars mark each character of a regular expression's set with `\N`;
- a scanner reuses each pattern's last search on a line, as vscode-oniguruma does: a search from an earlier start that
  found nothing, or a match at or after this start, is this search's answer too. Patterns with `\G` are searched each
  time. The tokenizer searches a line from one position after another, and each pattern searched to the line's end
  every time, so that a line took the square of its length: a minified JavaScript line of 60,000 characters took
  204 s, and takes about half a second;
- a line's UTF-8 offsets are looked up in a table (`OnigString.charToByteOffset`), where each call encoded the line up
  to the offset: with a Cyrillic letter in it, the same line took 280 s. A lone surrogate counts as the one byte that
  it's encoded as (`?`), both ways;
- compiled patterns are shared by every scanner, the 10,000 used last (`regex/CompiledPatterns.kt`). Each rule's
  scanner compiled its own, and grammars repeat their patterns in many rules: the 59 languages' samples compiled
  43,471 patterns, 4,074 of them different, and kept 327 MB, where they now keep 60 MB. Threads share them, as a
  forward search doesn't change a compiled Joni `Regex`.

## Differences from VS Code that remain

Measured on 2026-10-23 against vscode-textmate 9.3.2, the engine VS Code runs, with the shipped grammars:

- about 41,000 lines of files from Leaf and elsewhere (JSON, Kotlin, Leaf's `CLAUDE.md`, Python, XML, YAML, TOML,
  Java, Rust, shell, a Dockerfile, a minified JavaScript file), and shiki's own sample of each of 57 languages: every
  token the same, but on three lines of Ruby and Svelte;
- Leaf's samples (see Tests): 54 of 59 the same, and the rest only as below.

These patterns still never match, where VS Code matches them:

- a look-behind with an unbounded repeat, which no rewrite can spread: C#'s `await` after `.` (`(?<!\.\s*)`), Julia's
  `as`, Ruby's block parameters after `{ ` or `do `, `await using` in JavaScript and TypeScript, and the expressions
  of Svelte's `{#if …}` and `{#each …}` (`(?<=(if|each|…).*?)\G`);
- a back-reference in a look-behind: Markdown's `~~struck~~` text;
- an interval written backwards, `\s+{1,0}`, which Oniguruma 6.9 reads as a possessive `{0,1}` and Joni refuses: the
  start of a C or C++ documentation comment (`///`, `/**`), which stays a comment.

## Taking a later fix from kotlin-textmate

Compare kotlin-textmate's `core/src/` from `a500bc7a` to the commit with the fix, and apply that to `src/`. The commits
that changed this folder since it was added say what Leaf changed, so a conflict can be told from a fix that Leaf
already has. Then update the table above.

## Tests

```bash
./gradlew :textmate:test
```

- `FirstMateConformanceTest` runs first-mate's fixtures, which vscode-textmate tests itself against.
- `GoldenSnapshotTest` compares the tokens of kotlin-textmate's files in `tools/generate-golden/corpus/` (JSON,
  Kotlin, Markdown, JavaScript, with their own grammars) with goldens that vscode-textmate made. To make them again
  (Node.js 21.2 or newer):

  ```bash
  cd textmate/tools/generate-golden && npm install && npm run generate
  ```
- `ShippedGrammarsConformanceTest` tokenizes a sample of each language that Leaf colours
  (`src/test/resources/conformance/samples/<grammar>/`), and compares the tokens with vscode-textmate's for the same
  grammars (`expected/`). Only the lines that the differences above affect may differ, and they must, so that a fix
  shows. To make them again, after a change to the grammars or the samples:

  ```bash
  cd textmate/tools/conformance && npm ci && npm run generate
  ```
- `ShippedGrammarsTest` checks the index: each grammar's scope, what it embeds, its injections and its licence.
