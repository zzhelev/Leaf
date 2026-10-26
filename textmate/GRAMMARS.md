# Grammars

The TextMate grammars that Leaf ships, in `src/main/resources/grammars/`, as shiki's tm-grammars 1.32.22
publishes them, unmodified. `index.json` there lists each grammar's scope, the grammars it embeds and the ones it
injects into, and `NOTICE` has their licences. This file is written by `tools/grammars/copy.mjs`: to update the
grammars, change the version in `tools/grammars/package.json`, then run

```bash
cd textmate/tools/grammars && npm install && npm run copy
```

and regenerate the goldens (see README.md). The script refuses a grammar whose licence isn't permissive or
checked.

| Name | Scope | Licence | Source | Why shipped |
|---|---|---|---|---|
| abap | `source.abap` | TextMate bundle licence (permissive) | [pvl/abap.tmbundle](https://github.com/pvl/abap.tmbundle/blob/92e17e62a9449d2a6f87bfb30b4f920986c24018/Syntaxes/ABAP.tmLanguage) `Syntaxes/ABAP.tmLanguage` | markdown embeds it |
| bat | `source.batchfile` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/df15c8e832a341633acd2e9bb26e1b275d09cfe0/extensions/bat/syntaxes/batchfile.tmLanguage.json) `extensions/bat/syntaxes/batchfile.tmLanguage.json` | Leaf's list |
| bibtex | `text.bibtex` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/9a2c94d213696a4453b6d9988b003d98e7646e75/extensions/latex/syntaxes/Bibtex.tmLanguage.json) `extensions/latex/syntaxes/Bibtex.tmLanguage.json` | markdown embeds it |
| c | `source.c` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/bd1abe08c38bb5deccf65610f8f3c03d2338d20d/extensions/cpp/syntaxes/c.tmLanguage.json) `extensions/cpp/syntaxes/c.tmLanguage.json` | Leaf's list |
| clojure | `source.clojure` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/a1a4c0a63b1ad59e84f54b874744fe35dea9abe1/extensions/clojure/syntaxes/clojure.tmLanguage.json) `extensions/clojure/syntaxes/clojure.tmLanguage.json` | Leaf's list |
| cmake | `source.cmake` | MIT | [twxs/vs.language.cmake](https://github.com/twxs/vs.language.cmake/blob/19b1b8fe2d68d72cf294d0c3c22f0a3fc54f8f6f/syntaxes/CMake.tmLanguage) `syntaxes/CMake.tmLanguage` | Leaf's list |
| coffee | `source.coffee` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/ff58f5083f57b50e6a100ab60454dfb72fe21e80/extensions/coffeescript/syntaxes/coffeescript.tmLanguage.json) `extensions/coffeescript/syntaxes/coffeescript.tmLanguage.json` | vue embeds it |
| cpp | `source.cpp` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8623a79836fba2e7e058e3493345ee068d28ce4f/extensions/cpp/syntaxes/cpp.tmLanguage.json) `extensions/cpp/syntaxes/cpp.tmLanguage.json` | Leaf's list |
| cpp-macro | `source.cpp.embedded.macro` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8623a79836fba2e7e058e3493345ee068d28ce4f/extensions/cpp/syntaxes/cpp.embedded.macro.tmLanguage.json) `extensions/cpp/syntaxes/cpp.embedded.macro.tmLanguage.json` | cpp embeds it |
| csharp | `source.cs` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/csharp/syntaxes/csharp.tmLanguage.json) `extensions/csharp/syntaxes/csharp.tmLanguage.json` | Leaf's list |
| css | `source.css` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/css/syntaxes/css.tmLanguage.json) `extensions/css/syntaxes/css.tmLanguage.json` | Leaf's list |
| dart | `source.dart` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/dart/syntaxes/dart.tmLanguage.json) `extensions/dart/syntaxes/dart.tmLanguage.json` | Leaf's list |
| diff | `source.diff` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/4549bd26c7b799284e0ebd8dc1e0310e6a8707a1/extensions/diff/syntaxes/diff.tmLanguage.json) `extensions/diff/syntaxes/diff.tmLanguage.json` | Leaf's list |
| docker | `source.dockerfile` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8270a86019db7551da42b71d15c6080a414d8c81/extensions/docker/syntaxes/docker.tmLanguage.json) `extensions/docker/syntaxes/docker.tmLanguage.json` | Leaf's list |
| elixir | `source.elixir` | Apache-2.0 | [elixir-editors/elixir-tmbundle](https://github.com/elixir-editors/elixir-tmbundle/blob/43c8cd957d5ac6e1abbd8730fc7a08c81a6e76c9/Syntaxes/Elixir.tmLanguage) `Syntaxes/Elixir.tmLanguage` | Leaf's list |
| erlang | `source.erlang` | Apache-2.0 | [erlang-ls/grammar](https://github.com/erlang-ls/grammar/blob/fe898becd3004bcb4947ab50d6b6b830fbfb9228/Erlang.plist) `Erlang.plist` | Leaf's list |
| fsharp | `source.fsharp` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/e6cffb21362b6d7addb977ff7db14f0681228780/extensions/fsharp/syntaxes/fsharp.tmLanguage.json) `extensions/fsharp/syntaxes/fsharp.tmLanguage.json` | markdown embeds it |
| git-commit | `text.git-commit` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/f8c3f89468fea14103d8790d59ea8594d6e644eb/extensions/git-base/syntaxes/git-commit.tmLanguage.json) `extensions/git-base/syntaxes/git-commit.tmLanguage.json` | Leaf's list |
| git-rebase | `text.git-rebase` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/e95c74c4c7af876e79ec58df262464467c06df28/extensions/git-base/syntaxes/git-rebase.tmLanguage.json) `extensions/git-base/syntaxes/git-rebase.tmLanguage.json` | Leaf's list |
| go | `source.go` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/091ef378baaa141c8bc4bbe9775d4cb3bd655a80/extensions/go/syntaxes/go.tmLanguage.json) `extensions/go/syntaxes/go.tmLanguage.json` | Leaf's list |
| graphql | `source.graphql` | MIT | [prisma-labs/vscode-graphql](https://github.com/prisma-labs/vscode-graphql/blob/4569aef2364b42b6acd8188e4a6ca9b0ba3c6a1d/grammars/graphql.json) `grammars/graphql.json` | Leaf's list |
| groovy | `source.groovy` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8fdf170a0850c1cc027382f31650aaf300d3ae2a/extensions/groovy/syntaxes/groovy.tmLanguage.json) `extensions/groovy/syntaxes/groovy.tmLanguage.json` | Leaf's list |
| haml | `text.haml` | MIT | [karuna/haml-vscode](https://github.com/karuna/haml-vscode/blob/aa45415aefd691af0a501ea4f98e434490623e0d/syntaxes/haml.json) `syntaxes/haml.json` | ruby embeds it |
| handlebars | `text.html.handlebars` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/handlebars/syntaxes/Handlebars.tmLanguage.json) `extensions/handlebars/syntaxes/Handlebars.tmLanguage.json` | markdown embeds it |
| haskell | `source.haskell` | BSD-3-Clause | [octref/language-haskell](https://github.com/octref/language-haskell/blob/ae87982aac2bf5cf6334c067e49c5ec8fc08b871/syntaxes/haskell.json) `syntaxes/haskell.json` | Leaf's list |
| hcl | `source.hcl` | MPL-2.0 | [hashicorp/syntax](https://github.com/hashicorp/syntax/blob/2ec7cd10c9d6134e836bf85eb0f5aa010512bff4/syntaxes/hcl.tmGrammar.json) `syntaxes/hcl.tmGrammar.json` | Leaf's list |
| html | `text.html.basic` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/45324363153075dab0482312ae24d8c068d81e4f/extensions/html/syntaxes/html.tmLanguage.json) `extensions/html/syntaxes/html.tmLanguage.json` | Leaf's list |
| html-derivative | `text.html.derivative` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/4064f172528461afc6944383d07521f6a30da89c/extensions/html/syntaxes/html-derivative.tmLanguage.json) `extensions/html/syntaxes/html-derivative.tmLanguage.json` | vue embeds it |
| ini | `source.ini` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8fdf170a0850c1cc027382f31650aaf300d3ae2a/extensions/ini/syntaxes/ini.tmLanguage.json) `extensions/ini/syntaxes/ini.tmLanguage.json` | Leaf's list |
| java | `source.java` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/3c86ede5f554f6e196c832394e126b291a1de606/extensions/java/syntaxes/java.tmLanguage.json) `extensions/java/syntaxes/java.tmLanguage.json` | Leaf's list |
| javascript | `source.js` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/210541906e5a96ab39f9c753f921b1bd35f4138b/extensions/javascript/syntaxes/JavaScript.tmLanguage.json) `extensions/javascript/syntaxes/JavaScript.tmLanguage.json` | Leaf's list |
| json | `source.json` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/d6af4893ed9a3545163a4cb748fa5548bd1e51a5/extensions/json/syntaxes/JSON.tmLanguage.json) `extensions/json/syntaxes/JSON.tmLanguage.json` | Leaf's list |
| json5 | `source.json5` | MIT | [mrmlnc/vscode-json5](https://github.com/mrmlnc/vscode-json5/blob/e884b89929103ff009322c393080ff45a4544e88/syntaxes/json5.json) `syntaxes/json5.json` | Leaf's list |
| jsonc | `source.json.comments` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/0adddd82816db666c52038e8277312dc55da2756/extensions/json/syntaxes/JSONC.tmLanguage.json) `extensions/json/syntaxes/JSONC.tmLanguage.json` | Leaf's list |
| jsonl | `source.json.lines` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/0adddd82816db666c52038e8277312dc55da2756/extensions/json/syntaxes/JSONL.tmLanguage.json) `extensions/json/syntaxes/JSONL.tmLanguage.json` | Leaf's list |
| jsx | `source.js.jsx` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/210541906e5a96ab39f9c753f921b1bd35f4138b/extensions/javascript/syntaxes/JavaScriptReact.tmLanguage.json) `extensions/javascript/syntaxes/JavaScriptReact.tmLanguage.json` | Leaf's list |
| julia | `source.julia` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/julia/syntaxes/julia.tmLanguage.json) `extensions/julia/syntaxes/julia.tmLanguage.json` | Leaf's list |
| kotlin | `source.kotlin` | MIT | [fwcd/vscode-kotlin](https://github.com/fwcd/vscode-kotlin/blob/626e20d5c51a4379c4c0ee293aa89e58b5232aec/syntaxes/kotlin.tmLanguage.json) `syntaxes/kotlin.tmLanguage.json` | Leaf's list |
| latex | `text.tex.latex` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/latex/syntaxes/LaTeX.tmLanguage.json) `extensions/latex/syntaxes/LaTeX.tmLanguage.json` | Leaf's list |
| less | `source.css.less` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/03dc55030b3b2b5c4779d4ac1e2e46cd1f5a6b7c/extensions/less/syntaxes/less.tmLanguage.json) `extensions/less/syntaxes/less.tmLanguage.json` | Leaf's list |
| log | `text.log` | MIT | [emilast/vscode-logfile-highlighter](https://github.com/emilast/vscode-logfile-highlighter/blob/396c8552ff99d0e07142fdd940d75dae212dd28c/syntaxes/log.tmLanguage) `syntaxes/log.tmLanguage` | markdown embeds it |
| lua | `source.lua` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/c8fdd1650c06321d687ddfd1eff06aae4b616256/extensions/lua/syntaxes/lua.tmLanguage.json) `extensions/lua/syntaxes/lua.tmLanguage.json` | Leaf's list |
| make | `source.makefile` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/91db8ea85fee30ebb3c7a5e7939aa2a8d79811c5/extensions/make/syntaxes/make.tmLanguage.json) `extensions/make/syntaxes/make.tmLanguage.json` | Leaf's list |
| markdown | `text.html.markdown` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/6d8ab9737d58fc5eaf07e2ae6553b38183a5de47/extensions/markdown-basics/syntaxes/markdown.tmLanguage.json) `extensions/markdown-basics/syntaxes/markdown.tmLanguage.json` | Leaf's list |
| markdown-vue | `markdown.vue.codeblock` | MIT | [vuejs/language-tools](https://github.com/vuejs/language-tools/blob/784dd56026e07f1dadb9ef2498418eccbb3dee29/extensions/vscode/syntaxes/markdown-vue.json) `extensions/vscode/syntaxes/markdown-vue.json` | vue embeds it |
| objective-c | `source.objc` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/bd1abe08c38bb5deccf65610f8f3c03d2338d20d/extensions/objective-c/syntaxes/objective-c.tmLanguage.json) `extensions/objective-c/syntaxes/objective-c.tmLanguage.json` | Leaf's list |
| objective-cpp | `source.objcpp` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/bd1abe08c38bb5deccf65610f8f3c03d2338d20d/extensions/objective-c/syntaxes/objective-c++.tmLanguage.json) `extensions/objective-c/syntaxes/objective-c++.tmLanguage.json` | Leaf's list |
| perl | `source.perl` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/e637ff1bf7f481134849625e982ec80df9b35bc8/extensions/perl/syntaxes/perl.tmLanguage.json) `extensions/perl/syntaxes/perl.tmLanguage.json` | Leaf's list |
| php | `source.php` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/php/syntaxes/php.tmLanguage.json) `extensions/php/syntaxes/php.tmLanguage.json` | Leaf's list |
| postcss | `source.css.postcss` | MIT | [vuejs/vetur](https://github.com/vuejs/vetur/blob/8ccae3ba50b65837d4cd4acc71a3b84cd3bee27c/syntaxes/vue-postcss.json) `syntaxes/vue-postcss.json` | svelte embeds it |
| powershell | `source.powershell` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/powershell/syntaxes/powershell.tmLanguage.json) `extensions/powershell/syntaxes/powershell.tmLanguage.json` | Leaf's list |
| proto | `source.proto` | MIT | [zxh0/vscode-proto3](https://github.com/zxh0/vscode-proto3/blob/274be763ecae7055505354b44f4892a2669e1da8/syntaxes/proto3.tmLanguage.json) `syntaxes/proto3.tmLanguage.json` | Leaf's list |
| pug | `text.pug` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/6ac83c7c7dda9f27ca1fe9d98675253e010b75d1/extensions/pug/syntaxes/pug.tmLanguage.json) `extensions/pug/syntaxes/pug.tmLanguage.json` | vue embeds it |
| python | `source.python` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/cf4c9e469d521fa5f33353737e8157eb0789ad02/extensions/python/syntaxes/MagicPython.tmLanguage.json) `extensions/python/syntaxes/MagicPython.tmLanguage.json` | Leaf's list |
| r | `source.r` | MIT | [posit-dev/positron](https://github.com/posit-dev/positron/blob/c4d6bab60dd914427a2d47a4b64e75e025d94059/extensions/positron-r/syntaxes/r.tmGrammar.gen.json) `extensions/positron-r/syntaxes/r.tmGrammar.gen.json` | Leaf's list |
| raku | `source.perl.6` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8fdf170a0850c1cc027382f31650aaf300d3ae2a/extensions/perl/syntaxes/perl6.tmLanguage.json) `extensions/perl/syntaxes/perl6.tmLanguage.json` | markdown embeds it |
| regexp | `source.regexp.python` | MIT | [MagicStack/MagicPython](https://github.com/MagicStack/MagicPython/blob/c9b3409deb69acec31bbf7913830e93a046b30cc/grammars/MagicRegExp.tmLanguage) `grammars/MagicRegExp.tmLanguage` | cpp embeds it |
| rst | `source.rst` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/48e93f756b764991ede33ad09781a863aca6907a/extensions/restructuredtext/syntaxes/rst.tmLanguage.json) `extensions/restructuredtext/syntaxes/rst.tmLanguage.json` | markdown embeds it |
| ruby | `source.ruby` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/ruby/syntaxes/ruby.tmLanguage.json) `extensions/ruby/syntaxes/ruby.tmLanguage.json` | Leaf's list |
| rust | `source.rust` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/rust/syntaxes/rust.tmLanguage.json) `extensions/rust/syntaxes/rust.tmLanguage.json` | Leaf's list |
| sass | `source.sass` | MIT | [TheRealSyler/vscode-sass-indented](https://github.com/TheRealSyler/vscode-sass-indented/blob/f0e50cfaca4b64a1d49f8d938f188d6fd0bcfb6f/syntaxes/sass.tmLanguage.json) `syntaxes/sass.tmLanguage.json` | vue embeds it |
| scala | `source.scala` | MIT | [scala/vscode-scala-syntax](https://github.com/scala/vscode-scala-syntax/blob/672fa16c64e12b2cc8b98da011411463968de727/syntaxes/Scala.tmLanguage.json) `syntaxes/Scala.tmLanguage.json` | Leaf's list |
| scss | `source.css.scss` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/cf8d61ebd2f022f4ce8280171f0360d1fe0a206d/extensions/scss/syntaxes/scss.tmLanguage.json) `extensions/scss/syntaxes/scss.tmLanguage.json` | Leaf's list |
| shellscript | `source.shell` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/9473445f7d3dcb5c579f42ece8b6c18c43c63ed3/extensions/shellscript/syntaxes/shell-unix-bash.tmLanguage.json) `extensions/shellscript/syntaxes/shell-unix-bash.tmLanguage.json` | Leaf's list |
| sql | `source.sql` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/af600487b1e94374d9f48f57cbf2cad24656b07f/extensions/sql/syntaxes/sql.tmLanguage.json) `extensions/sql/syntaxes/sql.tmLanguage.json` | Leaf's list |
| stylus | `source.stylus` | MIT | [d4rkr00t/language-stylus](https://github.com/d4rkr00t/language-stylus/blob/198a851f385aee857332f3ce5dd981ff67720ead/syntaxes/stylus.json) `syntaxes/stylus.json` | vue embeds it |
| svelte | `source.svelte` | MIT | [sveltejs/language-tools](https://github.com/sveltejs/language-tools/blob/2cfcc15b4c44dfc1128432e20ca663fe20bdd12e/packages/svelte-vscode/syntaxes/svelte.tmLanguage.src.yaml) `packages/svelte-vscode/syntaxes/svelte.tmLanguage.src.yaml` | Leaf's list |
| swift | `source.swift` | MIT | [jtbandes/swift-tmlanguage](https://github.com/jtbandes/swift-tmlanguage/blob/2be007b6bc88e54a0edfd3c313ff31276edc923d/Swift.tmLanguage.yaml) `Swift.tmLanguage.yaml` | Leaf's list |
| terraform | `source.hcl.terraform` | MPL-2.0 | [hashicorp/syntax](https://github.com/hashicorp/syntax/blob/cc2b4d4fe389f14b8a13937f4e0d7b2811b57588/syntaxes/terraform.tmGrammar.json) `syntaxes/terraform.tmGrammar.json` | Leaf's list |
| tex | `text.tex` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/609f17a76734f092792a33c891af40bbcaea0c64/extensions/latex/syntaxes/TeX.tmLanguage.json) `extensions/latex/syntaxes/TeX.tmLanguage.json` | latex embeds it |
| toml | `source.toml` | TextMate bundle licence (permissive) | [textmate/toml.tmbundle](https://github.com/textmate/toml.tmbundle/blob/e82b64c1e86396220786846201e9aa3f0a2d9ca2/Syntaxes/TOML.tmLanguage) `Syntaxes/TOML.tmLanguage` | Leaf's list |
| tsx | `source.tsx` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/210541906e5a96ab39f9c753f921b1bd35f4138b/extensions/typescript-basics/syntaxes/TypeScriptReact.tmLanguage.json) `extensions/typescript-basics/syntaxes/TypeScriptReact.tmLanguage.json` | Leaf's list |
| typescript | `source.ts` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/210541906e5a96ab39f9c753f921b1bd35f4138b/extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json) `extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json` | Leaf's list |
| vb | `source.asp.vb.net` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/35d97bc7e439fce0f50f42074041ab2d8571b20a/extensions/vb/syntaxes/asp-vb-net.tmLanguage.json) `extensions/vb/syntaxes/asp-vb-net.tmLanguage.json` | markdown embeds it |
| vue | `text.html.vue` | MIT | [vuejs/language-tools](https://github.com/vuejs/language-tools/blob/cd7a6e53d58111640f116d1e19f1c1cf06c7157d/extensions/vscode/syntaxes/vue.tmLanguage.json) `extensions/vscode/syntaxes/vue.tmLanguage.json` | Leaf's list |
| vue-directives | `vue.directives` | MIT | [vuejs/language-tools](https://github.com/vuejs/language-tools/blob/784dd56026e07f1dadb9ef2498418eccbb3dee29/extensions/vscode/syntaxes/vue-directives.json) `extensions/vscode/syntaxes/vue-directives.json` | vue embeds it |
| vue-interpolations | `vue.interpolations` | MIT | [vuejs/language-tools](https://github.com/vuejs/language-tools/blob/784dd56026e07f1dadb9ef2498418eccbb3dee29/extensions/vscode/syntaxes/vue-interpolations.json) `extensions/vscode/syntaxes/vue-interpolations.json` | vue embeds it |
| vue-sfc-style-variable-injection | `vue.sfc.style.variable.injection` | MIT | [vuejs/language-tools](https://github.com/vuejs/language-tools/blob/e85556a2e4bf0d6c981bdbd95c8a0b1e85e7ae8a/extensions/vscode/syntaxes/vue-sfc-style-variable-injection.json) `extensions/vscode/syntaxes/vue-sfc-style-variable-injection.json` | vue embeds it |
| xml | `text.xml` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/10a1d2a50a2882f5ae85bdb51eb04d3064fb9de9/extensions/xml/syntaxes/xml.tmLanguage.json) `extensions/xml/syntaxes/xml.tmLanguage.json` | Leaf's list |
| xsl | `text.xml.xsl` | MIT | [microsoft/vscode](https://github.com/microsoft/vscode/blob/8fdf170a0850c1cc027382f31650aaf300d3ae2a/extensions/xml/syntaxes/xsl.tmLanguage.json) `extensions/xml/syntaxes/xsl.tmLanguage.json` | markdown embeds it |
| yaml | `source.yaml` | TextMate bundle licence (permissive) | [textmate/yaml.tmbundle](https://github.com/textmate/yaml.tmbundle/blob/e54ceae3b719506dba7e481a77cea4a8b576ae46/Syntaxes/YAML.tmLanguage) `Syntaxes/YAML.tmLanguage` | Leaf's list |
| zig | `source.zig` | MIT | [ziglang/vscode-zig](https://github.com/ziglang/vscode-zig/blob/9c7bb7631f3af99331475b633aaf5b70345c6713/syntaxes/zig.tmLanguage.json) `syntaxes/zig.tmLanguage.json` | Leaf's list |

Left out, though a shipped grammar embeds it; what it colours stays plain:

| Name | Why |
|---|---|
| gnuplot | GPL-3.0, which Leaf keeps out of its notices |
| glsl | its repository states no licence |

The licences that tm-grammars doesn't record were read in each repository, at the commit the grammar was taken
from:

- yaml: TextMate bundle licence (permissive), from its `README.mdown`.
- toml: TextMate bundle licence (permissive), from its `README.mdown`.
- abap: TextMate bundle licence (permissive), from its `README.md`.
- elixir: Apache-2.0, from its `LICENSE`.
- sass: MIT, from its `LICENSE`.
