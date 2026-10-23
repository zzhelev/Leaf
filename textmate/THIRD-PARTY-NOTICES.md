# Third-Party Notices

This module is a copy of kotlin-textmate's engine (see [README.md](README.md)), which includes and is derived from
third-party material. The original copyright notices and permission notices are retained below, as required by the
respective licenses. kotlin-textmate itself is licensed under the [MIT License](LICENSE).

## Ported source code

### vscode-textmate

kotlin-textmate is a Kotlin port of [vscode-textmate](https://github.com/microsoft/vscode-textmate) (baseline version
9.3.2, commit `25b68dad91920b1ed79d357534b8c4582f62d80d`). Substantial portions of this module's source are direct
translations of the original TypeScript.

> Copyright (c) Microsoft Corporation
>
> Licensed under the MIT License.

## Grammars used by the tests (`src/test/resources/grammars/`)

These TextMate grammar files are redistributed unmodified except for JSONC-to-JSON normalization. All are
MIT-licensed.

| File | Source | Copyright |
|---|---|---|
| `JSON.tmLanguage.json` | [microsoft/vscode-JSON.tmLanguage](https://github.com/microsoft/vscode-JSON.tmLanguage) | © Microsoft Corporation |
| `JavaScript.tmLanguage.json` | [microsoft/vscode](https://github.com/microsoft/vscode) (TypeScript-TmLanguage) | © Microsoft Corporation |
| `markdown.tmLanguage.json` | [microsoft/vscode-markdown-tm-grammar](https://github.com/microsoft/vscode-markdown-tm-grammar) | © Microsoft Corporation |
| `kotlin.tmLanguage.json` | [mathiasfrohlich/vscode-kotlin](https://github.com/mathiasfrohlich/vscode-kotlin) | © Mathias Fröhlich |

The fixtures in `src/test/resources/conformance/first-mate/` come from
[atom/first-mate](https://github.com/atom/first-mate), MIT-licensed, © GitHub Inc., through vscode-textmate's tests.

## Themes used by the tests (`src/test/resources/themes/`)

VS Code default color themes, redistributed with JSONC trailing commas stripped. Sourced from
[microsoft/vscode](https://github.com/microsoft/vscode) (`extensions/theme-defaults/themes/`), MIT-licensed,
© Microsoft Corporation.

- `dark_vs.json`, `dark_plus.json`, `light_vs.json`, `light_plus.json`

---

The MIT License text for all of the above is identical to the one in [LICENSE](LICENSE); only the copyright holders
differ.
