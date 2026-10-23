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

- the theme package (`dev.textmate.theme`) is left out, with its tests and themes: Leaf maps scopes to its own colours.

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
  vscode-textmate made. To make them again (Node.js 21.2 or newer):

  ```bash
  cd textmate/tools/generate-golden && npm install && npm run generate
  ```
