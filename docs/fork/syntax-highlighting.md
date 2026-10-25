# Syntax highlighting with TextMate grammars

| | |
|---|---|
| Question | How can Leaf colour code by file type (Kotlin, Java, JSON, XML, YAML, …) in as many formats as possible? |
| Verified against | `main` at `03e4d1e5`; kotlin-textmate 0.3.0 (`a500bc7a`); vscode-textmate 9.3.2; shiki's `tm-grammars` 1.32.22; Joni 2.2.7 |
| Date | 2026-10-23 |
| Status | Decided 2026-10-23 (section 3): the engine (TextMate grammars, a copy of kotlin-textmate kept in Leaf), palette 1a, a Code colours setting with Leaf, VS Code and IntelliJ, tag and attribute colours of their own, the grammar list, and no choice that turns colours off |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`, `T/` = the new module `textmate/` (step 1).

**Mockups:** the Claude Design project "Leaf colour palette" (`5fe7f140-cd30-47ab-a95a-1994cadae8d9`), file
`Syntax Colours.dc.html`. Turn 1: the palettes 1a, 1b and 1c in light and dark, 1a in Solarized and Teal, and the
style editor's Syntax group. Turn 2: the Code colours setting in Settings → Appearance, VS Code and IntelliJ as Leaf
draws them (2a, 2b), and VS Code in Solarized (2c). Each pane is Leaf's diff pane with four files, coloured from the
tokens that VS Code's engine gives for them.

## Summary

- **Today:** upstream's highlighter splits each line on spaces and checks the words against a keyword list, in 17
  languages. A line that starts with `//` is a comment; nothing else is: no strings, numbers, block comments or
  multi-line strings, and `if(` isn't a keyword. JSON, XML, YAML, Markdown, HTML, CSS and TOML get no colour.
- **Chosen (2026-10-23):** TextMate grammars, the format of VS Code, GitHub and shiki, run by kotlin-textmate, a Kotlin
  port of vscode-textmate (MIT, 29 files, about 100 KB of source), copied into Leaf so that Leaf can fix it. It reads
  regular expressions with Joni, the Java port of Oniguruma, so there's no native code.
- **What the probe found:** on Leaf's own files kotlin-textmate gives the same tokens as VS Code's engine for Kotlin
  (16,498 tokens), Java, JSON (145,501), XML (22,267), YAML, TOML and Dockerfile; Python differs in 1 token of 17,680.
  Shell scripts are its weak spot (56 of 83 lines uncoloured). About 100 ms for 10,000 lines of Kotlin, 560 ms for
  the 4,222 lines of `CLAUDE.md`, 100 to 300 ms to load a grammar the first time.
- **About 60 languages,** where Leaf has 17, from shiki's grammar collection, each with its licence recorded.
- **Nine syntax colours** where Leaf has three: keyword, annotation and comment as today, plus string, number, type,
  function, tag and attribute. Every style sets them, the editor shows them in a Syntax group, and each holds 4.5:1 on
  the background and both line tints.
- **A Code colours setting** under Style: Leaf (the default, palette 1a: each style's own code colours), VS Code and
  IntelliJ. The last two apply in every style, adjusted to the same 4.5:1.
- **Where:** the diff pane (unified and split, file history, a comparison's full view), blame, and the conflict
  resolver's rows and editor. Each version of a file is coloured whole, once, in the background; the text shows at once
  and its colours follow.

## 1. What Leaf has today

| Piece | Where | What happens to it |
|---|---|---|
| The highlighter and its 17 languages (2,000 lines, upstream's) | `A/ui/diff/syntax_highlighter/` (`SyntaxHighlighter`, `getSyntaxHighlighterFromExtension`) | Removed in step 7 |
| Unified and split diff lines | `DiffLineText` → `DiffText` → `formatDiff` in `A/ui/diff/DiffPane.kt` | Takes the spans of the line's version (step 5) |
| File history, a comparison's full view | Through `DiffPane` | Follow the diff pane |
| Blame | `A/ui/Blame.kt`, a line at a time | Whole file (step 6) |
| The resolver's rows | `LineText` in `A/ui/conflicts/ResolverSideways.kt`, from `ResolverColumnsView` and `ResolverStackedView` | Per version (step 6) |
| The resolver's editor | `EditorColours` in `A/ui/conflicts/ResolverEditor.kt` | The field's text (step 6) |
| Three syntax colours | `StyleSlot.KEYWORD`, `ANNOTATION`, `COMMENT` (group Diff), `ColorsScheme.diffKeyword` and the others, `slotRule` (Accent, Warning, Success) | Nine colours, group Syntax (step 4) |
| The style editor's preview | `A/ui/styles/StyleEditorPreview.kt`, keywords coloured by hand | The nine colours (step 4) |
| Limits | `MAX_DIFF_LINE_CHARACTERS` (10,000 shown), `MAX_HIGHLIGHTED_LINE_CHARACTERS` (1,000 coloured), `MAX_COLOURED_EDIT_CHARACTERS` (100,000) | Kept; the tokenizer gets its own (section 4.3) |

Every version a diff shows is already loaded whole: `Hunk.oldText` and `Hunk.newText` are the `RawText`s the diff
compared, blame has `resultContents`, and the resolver loads every stage. So no view has to read more than it does.

## 2. The probe (2026-10-23)

Run in the scratchpad against Leaf's own files and a few others, with the engines as published.

| | Leaf today | RSyntaxTextArea's lexers | TextMate, kotlin-textmate | Tree-sitter |
|---|---|---|---|---|
| Languages | 17 | 57 | 260 grammars in shiki's set | ~40, one native library each |
| 9,681 lines of Kotlin | — | 3 ms | 101 ms | not run |
| Kotlin's `"""` strings, `${…}` templates | No | No: `"""` is an error, `//` inside is a comment | Yes | Yes |
| Size | — | 1.35 MB | ~2.6 MB with ~60 grammars | ~1.7 MB a language |
| Native code | No | No | No | Yes, per platform |
| Licence | Upstream's | BSD-3 | MIT | MIT |

- **Same tokens as VS Code:** each file tokenized by kotlin-textmate and by vscode-textmate (node, the same grammar
  files), token by token: Kotlin, Java, JSON, XML, YAML, TOML, Dockerfile all equal; Python 1 of 17,680; Markdown 54 of
  30,053, all inside shell code blocks; Rust 41 of 1,376 (`std` in `use std::io` loses `entity.name.namespace`);
  a shell script 631 of 1,320, 56 of its 83 lines uncoloured.
- **Why:** Joni can't run some of Oniguruma's lookbehinds (variable length, a back-reference inside); kotlin-textmate
  turns such a rule into one that never matches. Counted per grammar: none in Kotlin, Java, JSON, XML, HTML, Go, C,
  TOML, SQL, Dockerfile, Groovy, Scala and Dart; a few in CSS, C#, Swift, Rust, Python and Markdown; shell is the one
  where it shows.
- **Loading:** kotlin-textmate's JSON reader (Gson, typed) refused 33 of the 260 grammars as published, XML's and
  Markdown's among them. Dropping the entries that VS Code ignores (a string where a capture should be an object) let
  257 load and tokenize.
- **Speed:** Kotlin about 95,000 lines a second, JSON 850,000, a workflow's YAML 255 lines in 11 ms, Markdown 7,500
  (it includes many languages). Loading a grammar and its first 200 lines: 116 ms for Kotlin, 316 ms for Markdown,
  103 ms for XML.
- **Not taken:** Eclipse's tm4e, the mature Java engine, is EPL-2.0 without a secondary licence, which can't be
  combined with Leaf's GPL and AGPL code. Highlights (Kotlin, Apache-2.0) has 17 languages and no JSON, XML, YAML or
  Markdown. highlight.js needs a JavaScript engine. Tree-sitter would bring back native libraries, which Leaf removed on
  2026-10-15.

## 3. Decisions

Made on 2026-10-23:

1. **The engine:** TextMate grammars, run by a copy of kotlin-textmate kept in Leaf.
2. **The palette:** 1a, Leaf's colours extended: today's keyword blue, annotation olive and comment green, and six
   colours in the hues between them; tags take the keyword's colour and attributes the type's, so XML, JSON and YAML
   read in code's colours. It's the default, and the one built-in styles carry.
3. **Today's light colours get darker,** as 1a has them: the light keyword #3B83CF, annotation #9F9927 and comment
   #0D9141 hold 3.9, 3.0 and 4.1:1 on white, and 2.4 to 3.3 on a removed line; 1a has #1A69B5, #756700 and #027835. In
   dark the keyword stays #A3CAF1, and annotation and comment get a touch lighter (#C9BC6F, #86CE99). This changes
   Classic, which has kept Leaf's old colours exactly so far.
4. **Syntax colours are checked:** 4.5:1 on the background, the added lines and the removed lines, like text, so no
   style or code-colour choice leaves code unreadable. Today they aren't checked. Not on the word highlights: in dark,
   the added words' bright green leaves even white text at 5.0.
5. **A Code colours setting** (section 4.5): Leaf, VS Code and IntelliJ, under Style. VS Code and IntelliJ apply in
   every style, in place of the style's own code colours, and are adjusted to 4.5:1 like any colour. As published,
   both fall to 2 or 3:1 on Leaf's diff tints (VS Code's dark keyword 3.0, IntelliJ's dark comment 2.1).
6. **Tags and attributes have colours of their own,** set by default to the keyword's and the type's, so a style can
   tell them apart.
7. **The grammars:** the list in section 4.1. Licences MIT, Apache-2.0, BSD and MPL-2.0. YAML's and TOML's come from
   TextMate's own bundles and Elixir's from its editors' bundle, whose licences shiki's index doesn't record: they ship
   once checked. nginx's is GPL-3.0 and is left out, to keep the notices to permissive licences.
8. **No choice that turns colours off:** a style can set any syntax colour to its text colour.

## 4. Behaviour

### 4.1 Languages

A file's language comes from Leaf's own table (`D/highlighting/Languages.kt`), in this order: its whole name
(`Dockerfile`, `Makefile`, `CMakeLists.txt`, `Jenkinsfile`, `Gemfile`, `.bashrc`, `.zshrc`), then its extensions, the
longest first (`.gradle.kts` is Kotlin, `.d.ts` TypeScript), then the first line's `#!` (`python3`, `bash`, `node`).
Anything else stays plain, as today.

The grammars, about 60: Kotlin, Java, Groovy, Scala, Swift, Objective-C, Objective-C++, C, C++, C#, Go, Rust, Dart,
Python, Ruby, Perl, PHP, Lua, R, Julia, Haskell, Erlang, Elixir, Clojure, Zig, JavaScript, TypeScript, JSX, TSX,
Vue, Svelte, HTML, CSS, SCSS, Less, JSON, JSON with comments, JSON5, JSON Lines, XML (also `.svg`, `.plist`, Android
layouts), YAML, TOML, INI (also `.properties`, `.editorconfig`), Markdown, LaTeX, SQL, GraphQL, Protocol Buffers,
Shell (bash, zsh, sh), PowerShell, Batch, Dockerfile, Makefile, CMake, HCL and Terraform, Diff, git's commit message
and rebase list.

Markdown's code blocks are coloured in their own language, as the grammar includes the others.

### 4.2 Categories

A token takes the colour of the first rule in this table that its innermost scope matches, then its parent scope, and so
on outwards. Rules match a scope or its children (`string` matches `string.quoted.double.kotlin`).

| Scopes | Colour |
|---|---|
| `comment`, `punctuation.definition.comment`, `keyword.other.documentation` | Comment |
| `punctuation.definition.template-expression`, `punctuation.section.embedded`, `variable.string-escape` (`${`, `}`, `$c`) | Keyword |
| `meta.template.expression`, `meta.embedded` (code inside a template) | Plain, then its own tokens |
| `string`, `punctuation.definition.string`, `constant.character.escape`, `markup.inline.raw`, `markup.fenced_code` | String |
| `constant.numeric`, `constant.language`, `constant.character` (`true`, `null`, `&amp;`), `keyword.other.unit` (`px` in `8px`)\* | Number |
| `keyword.operator`, `storage.type.function.arrow` | Plain |
| `storage.modifier.import.java`, `variable.language.wildcard.java`, `storage.modifier.package.java` (Java's package names)\* | Plain |
| `entity.name.type.annotation`, `storage.type.annotation`, `meta.decorator` | Annotation |
| `support.type.property-name`, `entity.other.attribute-name`, `entity.name.tag.yaml` (keys) | Attribute |
| `entity.name.tag`, `punctuation.definition.tag` | Tag |
| `entity.name.namespace`, `entity.other.attribute`, `entity.name.scope-resolution`, `entity.name.class`, and the types that Go's, Java's and Groovy's grammars scope as `storage.type` (`storage.type.java`, `storage.type.primitive.java`, `storage.type.string.go`, …)\* | Type |
| `markup.heading`, `keyword`, `storage.type`, `storage.modifier` | Keyword |
| `entity.name.type`, `entity.other.inherited-class`, `support.type`, `support.class` | Type |
| `entity.name.function`, `support.function` | Function |

The rows marked \* are VS Code's: its Dark+ and Light+ themes have them, and without them Java's types and package
names took the keyword's colour, and CSS's units another colour than their numbers (added in step 3). Its entries that
no shipped grammar has are left out, and so is the plan's `meta.annotation`, for the same reason.

Markdown's `markup.bold` and `markup.italic` are drawn bold and italic, and headings bold. A grammar's quirks show as
they do in VS Code: Kotlin's grammar colours `class` in `Foo::class` as a function, and `value` as a keyword wherever
it appears (it is one in `value class`). A grammar can be patched where that misleads.

### 4.3 Colouring a file

- **Whole versions, once.** Each version a view shows is tokenized from its first line, so a block comment or a
  multi-line string that starts above a hunk is coloured right. A removed line takes the old version's colours, an
  added or a context line the new version's.
- **In the background.** The text shows at once; its colours arrive when the version is done, without moving anything.
  Results are kept by content (a blob id where the view has one), so stepping through commits doesn't tokenize the
  same file twice.
- **Limits, set from measurements in step 3:** 2 s per file (`HIGHLIGHT_TIME_LIMIT_MS`, as the resolver's), after
  which the rest stays plain; lines past 10,000 characters (`MAX_TOKENIZED_LINE_LENGTH`; VS Code stops at 20,000)
  aren't tokenized, and the next line starts in the state that line started in; the existing
  `MAX_HIGHLIGHTED_LINE_CHARACTERS` stays for drawing. kotlin-textmate has no time limit (its issue 17); the copy gets
  vscode-textmate's `timeLimit`, checked inside a line, as one pathological line can take seconds.
- **A failure** (a grammar that doesn't load, a regex that throws) leaves the file plain and is logged once per grammar.

### 4.4 Colours

- **Nine slots:** `KEYWORD`, `ANNOTATION`, `COMMENT` (as today, file keys unchanged) and `STRING`, `NUMBER`, `TYPE`,
  `FUNCTION`, `TAG`, `ATTRIBUTE` (keys `string`, `number`, `type`, `function`, `tag`, `attribute`), in a new group
  Syntax. The Diff group keeps the four line colours.
- **The template** (the looks' built-in values) is 1a's. Classic, Tonal and Quiet share it, as they share today's
  three.
- **What each follows:** keyword and tag the Accent seed, annotation Warning, comment Success, as the three do today;
  string, number, type, function and attribute are fixed, and the checks move them on a background that needs it.
- **Built-ins:** Solarized takes its own accents for the new colours (cyan strings, magenta numbers, violet types,
  orange functions), worked out to hold the checks, which darkens them on the light side and lifts them on the dark
  side. Teal's keyword follows its teal accent, so its function is set by hand to 1a's blue: following the template,
  it would be a cyan 0.017 from the keyword in OKLab.
- **Files:** a style file without the new keys reads them from its look, as any missing colour; an older Leaf ignores
  them (logged, as an unknown key).
- **The editor:** a Syntax group in Advanced, the nine rows, light and dark. The preview's diff lines get a string, a
  number and a type.
- `ColorsScheme` (upstream's) keeps its three fields; the six new colours live in a fork-only `SyntaxColors`
  (`LocalSyntaxColors`), as `StyleColors` does for the roles.

### 4.5 The Code colours setting

- **Where:** Settings → Appearance, a drop-down after the Style gallery and before Lists spacing (`SettingDropDown`).
  Title "Code colours", subtitle "How code is coloured in diffs, blame and the conflict resolver. Leaf uses each
  style's own colours.", choices "Leaf", "VS Code" and "IntelliJ".
- **Stored** as `code_colours` in `user_prefs.json` (`AppConfig.CodeColours`, `D/styles/CodeColours.kt`): `leaf`,
  `vscode` or `intellij`. No key, or a value that this version doesn't know, reads as Leaf (as `ThemeMapper` and
  `LinesHeightMapper` do), so a choice added later never stops an older Leaf at launch.
- **Leaf** draws each style's own nine colours: 1a in Classic, Tonal, Quiet and Teal, Solarized's in Solarized, and
  whatever a user's style has.
- **VS Code and IntelliJ** replace the nine colours in every style, colours set by hand included, so the choice always
  shows. Their published values (the table below) go in as if worked out: the checks of decision 4 move each one in
  lightness, keeping its hue, until it holds 4.5:1 on that style's background and line tints. IntelliJ leaves types
  plain, so its type is the style's text colour.
- **The style editor:** the Syntax rows keep editing the style's own colours, which show with Leaf. Its previews draw
  what the app draws, so they follow the setting.

| | VS Code light | VS Code dark | IntelliJ light | IntelliJ dark |
|---|---|---|---|---|
| Keyword | #0000FF | #569CD6 | #0033B3 | #CF8E6D |
| Annotation | #267F99 | #4EC9B0 | #9E880D | #B3AE60 |
| Comment | #008000 | #6A9955 | #8C8C8C | #7A7E85 |
| String | #A31515 | #CE9178 | #067D17 | #6AAB73 |
| Number | #098658 | #B5CEA8 | #1750EB | #2AACB8 |
| Type | #267F99 | #4EC9B0 | text | text |
| Function | #795E26 | #DCDCAA | #00627A | #56A8F5 |
| Tag | #800000 | #569CD6 | #0033B3 | #D5B778 |
| Attribute | #E50000 | #9CDCFE | #871094 | #C77DBB |

VS Code's are its Light Modern and Dark Modern themes, IntelliJ's the Light and Dark schemes of IntelliJ IDEA and
Android Studio. VS Code gives `if`, `return` and `when` a purple of their own, and IntelliJ colours only a function's
declaration; Leaf's one keyword colour and one function colour fold those in.

**As Leaf draws them in Tonal** (turn 2 of the board): in light, five of VS Code's colours darken a shade (#267F99 →
#0E708A, #008000 → #007900) and IntelliJ's grey comment goes from #8C8C8C to #676767. In dark both get lighter where
they meet the line tints: VS Code's keyword #569CD6 → #7AC1FD and comment #6A9955 → #97C882, IntelliJ's comment
#7A7E85 → #B6BBC2, so it recedes less than in IntelliJ. On Solarized's dark background they move less.

## 5. Implementation

Seven steps; 2 needs 1, 3 needs 2, 5 and 6 need 3 and 4. Each builds and passes its tests.

### Step 1: the engine in Leaf (`T/`)

- A fork-only Gradle module `textmate`, on the `buildsrc` convention: kotlin-textmate's `core` at `a500bc7a`, its 29
  files in their own package `dev.textmate`, with the MIT licence, its copyright lines and its third-party notice
  (vscode-textmate's) kept. `README.md`'s License section says which files are MIT.
- **Changes to the copy,** each a commit of its own so that later upstream fixes can be compared: Gson out, the reader
  on kotlinx.serialization, lenient as vscode-textmate is (an entry of the wrong type is skipped); vscode-textmate's
  `timeLimit`; the theme package out (Leaf maps scopes itself); the shell grammar's gap found and fixed in the engine,
  or the grammar patched.
- **New dependencies (rule 4 of `PLAN.md`):** Joni 2.2.7 (230 KB) and jcodings 1.0.64 (1.8 MB), MIT, JRuby's.
- **Tests:** its own (conformance against first-mate's fixtures, its goldens), and a conformance test of Leaf's: for
  each shipped grammar, sample files (`T/src/test/resources/conformance/`) whose tokens must equal goldens made by
  vscode-textmate. A node script in `T/tools/` regenerates the goldens; CI only compares.
- **Done (2026-10-23)**, as above, with these differences:
  - The tests run on JUnit 5, as Leaf's do.
  - The shell gap was fixed in the engine, and more with it: one look-behind in shell's rule for a command had a group
    of alternatives of different lengths, which Joni (an older Oniguruma) refuses. `LookbehindRewrite` spreads such a
    look-behind into top-level alternatives, which match the same text. That fixes 173 of the 351 patterns Joni
    refused in shiki's grammars: also CSS's selectors, Rust's paths, JavaScript's and TypeScript's imports, C's and
    C++'s line continuations. The probe's files, shell included, now give VS Code's tokens, but one in Python.
  - The goldens are kotlin-textmate's (JSON, Kotlin, Markdown, JavaScript) and Leaf's (shell, CSS, Rust), the grammars
    in `T/src/test/resources/grammars/`; step 2 adds one for each shipped grammar.
  - The differences that remain are in `textmate/README.md`: look-behinds with an unbounded repeat (C#'s `await`,
    Julia's `as`, Ruby's block parameters, `await using`) and C++'s backwards interval `\s+{1,0}`.

### Step 2: grammars and languages

- `T/src/main/resources/grammars/`: the grammars of section 4.1 from shiki's `tm-grammars` at a pinned version, as
  published (about 2.7 MB, 360 KB compressed in the jar), with `T/GRAMMARS.md`: each grammar's source, commit and
  licence. `T/tools/` has the script that copies them and writes that file.
- `D/highlighting/Languages.kt` (pure): file name and first line to a grammar's scope name, and the list of grammars.
  `LanguagesTest`: names, extensions, the longest extension first, shebangs, case, unknown files.
- **Done (2026-10-23)**, as above, with these differences and additions:
  - 82 grammars: the 59 languages of section 4.1 and the 23 they embed (Markdown's code blocks, Vue's and Svelte's
    style languages, C++'s macros, Vue's injections). 5.2 MB of JSON, 0.56 MB compressed in the jar: more than the
    plan's estimate, as C++'s grammar and its macros are 1.2 MB. `T/tools/grammars/copy.mjs` copies them, writes
    `index.json` (scopes, embeds, injections) and `NOTICE` (each grammar's licence, which goes into the jar), and
    refuses a grammar whose licence is neither permissive nor checked.
  - Licences checked: YAML's, TOML's and ABAP's are TextMate's bundle licence ("Permission to copy, use, modify, sell
    and distribute this software is granted"), Elixir's Apache-2.0, Sass's MIT. Left out: gnuplot (GPL-3.0, embedded
    by LaTeX) and GLSL (no licence stated, embedded by C++).
  - `ShippedGrammars` (`T/`, `dev.app.leaf.textmate`) loads them, and adds the grammars that inject into one, as VS
    Code does by their `injectTo`. Step 3 builds on it.
  - `Languages.kt` names 74 languages: the 59, and 15 embedded grammars with files of their own (CoffeeScript, F#,
    Handlebars, Haml, Pug, Raku, reStructuredText, VB, Sass, Stylus, PostCSS, logs, ABAP, BibTeX, XSLT). `.h` is C++,
    as in VS Code.
  - Conformance: a sample per language in `T/src/test/resources/conformance/samples/`, whose tokens
    `ShippedGrammarsConformanceTest` compares with vscode-textmate's (`T/tools/conformance/`). 54 of 59 match; the
    rest are listed in `textmate/README.md`. shiki's own samples of 57 languages, measured but not kept (they come
    from many projects), match but for three lines of Ruby and Svelte.
  - One more engine change: `\N`, which Oniguruma 6.9 reads as any character but a newline and Joni as the letter,
    is rewritten (`EscapeRewrite`). Python's regular expressions then match VS Code.
  - Step 1's shell, CSS and Rust goldens moved to the shipped grammars, with their test copies of those grammars gone.

### Step 3: the tokenizing service

- `D/highlighting/`: `SyntaxCategory` (the nine), `SyntaxSpan(start, end, category, bold, italic)`,
  `HighlightedText` (a list of spans per line), and `syntaxCategoryOf(scopes)` with section 4.2's table, tested against
  the goldens' scopes.
- `D/interfaces/IHighlightTextAction` and `HighlightTextUseCase(path, text)`; `DA/highlighting/TextMateHighlighter`, an
  app singleton: grammars loaded once each; one registry per worker thread, as a `Grammar` isn't thread-safe; a
  dispatcher of two threads; results kept by content, least recently used first out; the time limit, the line limit and
  cancellation (a view that moves on stops the work).
- **Measured, to set the limits:** 10,000 lines of Kotlin, the 4,222-line `CLAUDE.md`, 28,000 lines of JSON, a minified
  JavaScript file, a 10 MB lock file; cold and warm.
- **Done (2026-10-25)**, as above, with these findings and differences:
  - **Measured**, on this Mac, with the changes below (cold is a new highlighter, whose threads compile the grammar):

    | File | Lines | Spans kept | Cold | Warm |
    |---|---|---|---|---|
    | Leaf's Kotlin | 10,000 | 12,941 (190 KB) | 238 ms | 70 ms |
    | `CLAUDE.md` | 4,460 | 5,767 (85 KB) | 613 ms | 429 ms |
    | Two grammars, pretty-printed JSON | 31,598 | 25,553 (422 KB) | 91 ms | 88 ms |
    | A `package-lock.json` | 260,013 (9.6 MB) | 364,015 (5.3 MB) | 652 ms | 581 ms |
    | vscode-textmate's minified `main.js` | 1 of 61,197 characters | 88 | 1.67 s | 1.62 s |
    | The same 8 times | 1 of 489,583 characters | 87 | 11.7 s | 11.9 s |

    So the time limit is 2 s, which all but the minified files stay well within, and lines past 10,000 characters
    aren't tokenized: the diff pane shows that much of a line and colours its first 1,000, and minified JavaScript
    takes about 25 µs a character, so such a line takes a quarter of a second, where 20,000 would take half. The
    kept results are 12 bytes a span and 4 a line, up to 32 MB (`HIGHLIGHT_CACHE_BYTES`).
  - **Three engine fixes** came out of it (`textmate/README.md`): the minified line took 204 s, as each search ran to
    the line's end, and 280 s with a Cyrillic letter in it, as each offset was found by encoding the line up to it;
    and each thread kept every grammar it had compiled, at up to 41 MB each (TypeScript's), because each rule compiled
    its own copy of patterns that grammars repeat. Sharing compiled patterns took the 59 languages from 327 MB to
    60 MB, and each thread keeps the 12 grammars it used last (`MAX_GRAMMARS_PER_THREAD`).
  - **The categories** are worked out once per scope (`infoOf`): checking every scope of every token against the table
    took four times as long as tokenizing JSON, whose scopes nest deep. Three rows of VS Code's themes were added to
    section 4.2's table.
  - **A registry per grammar.** `Registry` gives a grammar every injection grammar it holds, not only those whose
    `injectTo` names it, so each grammar a thread compiles gets a registry of its own, with only its injections, as
    `ShippedGrammars.loadGrammar` sets them up.
  - **Not by blob id:** results are kept by the grammar and a SHA-256 of the text, so the use case takes only the path
    and the text.
  - VS Code's Markdown grammar colours fenced blocks in 60 languages, Java, Python and shell among them, but not
    Kotlin; a Kotlin block is drawn in the string colour, as in VS Code.
  - Tests: `SyntaxCategoriesTest` (the categories of tokens of 17 languages' samples, from the scopes in their
    goldens, and that every scope of the table is in a shipped grammar), `HighlightedTextTest`,
    `HighlightTextUseCaseTest` (domain), and `TextMateHighlighterTest` (data: each limit, the cache, cancellation, a
    grammar that fails, and the samples coloured on both threads at once as one at a time). 21 mutations were each
    caught by a named test.

### Step 4: the colours

- `StyleSlot`'s six new entries and group Syntax; the template's nine values for each side; `slotRule`; the checks in
  `contrastChecks`; `BuiltInStyles` for Solarized and Teal; `SyntaxColors` and `LocalSyntaxColors` from the resolved
  palette; the editor's group and strings; the preview.
- **The setting:** `D/styles/CodeColours.kt` (the choices and the two published tables, as ARGB ints, as the styles
  keep theirs), `AppConfig.CodeColours`, its mapper and key, and the drop-down. `resolveStyleSide` takes the choice and
  puts the table's colours in the nine syntax slots before the checks run. `App.kt` passes the setting to `AppTheme`
  with the style. The settings dialog's state already `combine`s 30 flows, the most `common` has overloads for, so the
  setting joins an existing flow (the style's), as the commit settings did.
- **Tests:** `StyleDerivationTest` (every built-in's nine colours exactly; 2,000 random seed sets per look and side hold
  the new checks; Teal's function apart from its keyword; each code-colour choice in every built-in holds the checks and
  keeps each published colour's hue; with Leaf nothing changes), `StyleFileCodecTest` (old files, new keys, an older
  reader), `CodeColoursSettingTest` (data: a restart, no key, an unknown value), `StyleEditorDialogTest`, and the
  Appearance page's new row, rendered. Renders of the main panes against `main`'s: only syntax colours differ.

### Step 5: the diff pane

- The view models that load a text diff (`RepositoryOpenViewModel`, `HistoryViewModel`, the comparison's full view)
  start `HighlightTextUseCase` for the old and the new version, and expose `DiffHighlights` next to the diff.
  `DiffPane` provides it as `LocalDiffHighlights`, so upstream's composables between the pane and the line don't change.
- `DiffLineText` picks the line's spans (old version for removed lines, new otherwise); `formatDiff` takes spans in
  place of a `SyntaxHighlighter`, maps their offsets onto the text as drawn (a tab as four spaces, the line ending
  dropped, the cut at 10,000 characters), and keeps the word highlights and the whitespace marks as they are.
- **Tests:** `FormatDiffTest` (spans with tabs, a cut line, word highlights over colours), `DiffSyntaxTest` (rendered:
  each category's colour in both themes, a removed line from the old version, a block comment that starts above the
  hunk, colours that arrive after the text, a file with no grammar).

### Step 6: blame and the conflict resolver

- Blame tokenizes the file once.
- The resolver highlights each stage when a file loads, and passes the result to the rows as a parameter, as it passes
  `ChangedWords` (a `CompositionLocal` there threw "pending composition has not been applied" in a scene). The result's
  rows take their side's spans; lines typed in a part are tokenized from the state the part's first line had.
- The editor (`EditorColours`) tokenizes the field's text as it changes, up to `MAX_COLOURED_EDIT_CHARACTERS`.
- **Tests:** the rows' colours in both layouts in `ConflictResolverTest`, `EditorColoursTest`, blame's lines.

### Step 7: removing the old highlighter, and notes

- Upstream's `syntax_highlighter` package and `SyntaxHighlighterTest` go once nothing uses them. Upstream hasn't
  changed these files since its 2.0 rewrite, so a later change there is a rare modify/delete conflict.
- `CLAUDE.md` (a section on highlighting), `docs/fork/CHANGELOG.md`, and a demo repository with one file of each
  language for a manual run.

## 6. Checks before calling it done

- The steps' suites, then `./gradlew build`.
- The conformance test for every shipped grammar, and the list of the differences from VS Code that remain (shell, if
  not fixed).
- Mutations of the category table, the version picked for a removed line, the offset mapping and the time limit, each
  caught by a named test.
- Renders of the diff pane, blame and the resolver in every built-in style, light and dark, against the board.
- A dev run on Leaf itself and on a large repository: stepping through commits in the log with a diff open stays as
  fast as today.

## 7. Later

- A file's language from `.gitattributes` (`linguist-language`), as GitHub reads it.
- Grammars a user adds: `.tmLanguage.json` files in `<app data>/grammars`.
- Colouring the commit message field with git's commit grammar.
