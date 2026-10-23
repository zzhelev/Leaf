// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

// Copies the grammars that Leaf ships from shiki's tm-grammars, at the version in package.json, into the module's
// resources, with their index and their licences, and writes GRAMMARS.md.
// Usage: cd textmate/tools/grammars && npm ci && npm run copy
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "fs";
import { join } from "path";
import { grammars, injections } from "tm-grammars";

const MODULE = join(import.meta.dirname, "..", "..");
const PACKAGE = join(import.meta.dirname, "node_modules", "tm-grammars");
const OUTPUT = join(MODULE, "src", "main", "resources", "grammars");
const VERSION = JSON.parse(readFileSync(join(PACKAGE, "package.json"), "utf8")).version;

// The languages that Leaf colours (docs/fork/syntax-highlighting.md, section 4.1). The grammars they embed come along.
const LANGUAGES = `kotlin java groovy scala swift objective-c objective-cpp c cpp csharp go rust dart python ruby
  perl php lua r julia haskell erlang elixir clojure zig javascript typescript jsx tsx vue svelte html css scss less
  json jsonc json5 jsonl xml yaml toml ini markdown latex sql graphql proto shellscript powershell bat docker make
  cmake hcl terraform diff git-commit git-rebase`.trim().split(/\s+/);

// Embedded grammars that aren't shipped: what they colour stays plain.
const LEFT_OUT = {
  gnuplot: "GPL-3.0, which Leaf keeps out of its notices",
  glsl: "its repository states no licence",
};

// Licences that tm-grammars doesn't record, read in each grammar's repository at the commit it was taken from.
const CHECKED = {
  yaml: { license: "TextMate bundle licence (permissive)", file: "README.mdown" },
  toml: { license: "TextMate bundle licence (permissive)", file: "README.mdown" },
  abap: { license: "TextMate bundle licence (permissive)", file: "README.md" },
  elixir: { license: "Apache-2.0", file: "LICENSE" },
  sass: { license: "MIT", file: "LICENSE" },
};

const PERMISSIVE = new Set(["MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "MPL-2.0"]);

const byName = new Map([...grammars, ...injections].map((g) => [g.name, g]));
const shipped = new Map();
const queue = LANGUAGES.map((name) => [name, "Leaf's list"]);
while (queue.length > 0) {
  const [name, reason] = queue.shift();
  if (shipped.has(name) || name in LEFT_OUT) continue;
  const grammar = byName.get(name) ?? fail(`No grammar named ${name} in tm-grammars ${VERSION}`);
  const license = CHECKED[name]?.license ?? grammar.license;
  if (!PERMISSIVE.has(license) && !(name in CHECKED)) {
    fail(`${name} has the licence ${license}: check it and add it to CHECKED, or to LEFT_OUT`);
  }
  shipped.set(name, { grammar, license, reason });
  for (const embedded of grammar.embedded ?? []) queue.push([embedded, `${name} embeds it`]);
}

rmSync(OUTPUT, { recursive: true, force: true });
mkdirSync(OUTPUT, { recursive: true });
const names = [...shipped.keys()].sort();
for (const name of names) copyFileSync(join(PACKAGE, "grammars", `${name}.json`), join(OUTPUT, `${name}.json`));

const index = names.map((name) => {
  const { grammar } = shipped.get(name);
  return {
    name,
    scopeName: grammar.scopeName,
    file: `${name}.json`,
    listed: LANGUAGES.includes(name),
    embedded: (grammar.embedded ?? []).filter((e) => shipped.has(e)),
    injectTo: grammar.injectTo ?? [],
  };
});
writeFileSync(join(OUTPUT, "index.json"), `[\n${index.map((e) => "  " + JSON.stringify(e)).join(",\n")}\n]\n`);
writeFileSync(join(OUTPUT, "NOTICE"), notice());
writeFileSync(join(MODULE, "GRAMMARS.md"), grammarsMd());
console.log(`${names.length} grammars from tm-grammars ${VERSION} -> ${OUTPUT}`);

/** tm-grammars' NOTICE, cut to the shipped grammars, with the licences read for those it doesn't record. */
function notice() {
  const sections = readFileSync(join(PACKAGE, "NOTICE"), "utf8").split(/^={105}$/m).slice(1);
  const out = [
    "THIRD-PARTY NOTICES FOR THE TEXTMATE GRAMMARS IN THIS FOLDER",
    "",
    `These grammars are copied, unmodified, from tm-grammars ${VERSION}`,
    "(https://github.com/shikijs/textmate-grammars-themes), which collected them from the projects named below.",
    "They are licensed under the licences below.",
  ];
  for (const section of sections) {
    const match = section.match(/^\s*Files:\s+(.*)$/m);
    if (!match) continue;
    const files = match[1].split(/,\s*/).filter((file) => {
      const name = file.replace(/\.json$/, "");
      return shipped.has(name) && !(name in CHECKED);
    });
    if (files.length === 0) continue;
    out.push("", "=".repeat(105), section.replace(match[0], `Files:   ${files.join(", ")}`).trim());
  }
  for (const [name, { license, file }] of Object.entries(CHECKED)) {
    if (!shipped.has(name)) continue;
    const { grammar } = shipped.get(name);
    const repository = grammar.source.split("/blob/")[0];
    out.push(
      "",
      "=".repeat(105),
      `Files:   ${name}.json`,
      `License: ${repository}/blob/${grammar.sha}/${file}`,
      `SPDX:    ${license.startsWith("TextMate") ? "LicenseRef-TextMate-bundle" : license}`,
      "-".repeat(105),
      readFileSync(join(import.meta.dirname, "licenses", `${name}.txt`), "utf8").trim(),
    );
  }
  return out.join("\n") + "\n";
}

function grammarsMd() {
  const rows = names.map((name) => {
    const { grammar, license, reason } = shipped.get(name);
    const [repository, path] = grammar.source.replace("https://github.com/", "").split(/\/blob\/[0-9a-f]+\//);
    const source = `[${repository}](${grammar.source}) \`${path}\``;
    return `| ${name} | \`${grammar.scopeName}\` | ${license} | ${source} | ${reason} |`;
  });
  const leftOut = Object.entries(LEFT_OUT).map(([name, why]) => `| ${name} | ${why} |`);
  return [
    "# Grammars",
    "",
    `The TextMate grammars that Leaf ships, in \`src/main/resources/grammars/\`, as shiki's tm-grammars ${VERSION}`,
    "publishes them, unmodified. `index.json` there lists each grammar's scope, the grammars it embeds and the ones it",
    "injects into, and `NOTICE` has their licences. This file is written by `tools/grammars/copy.mjs`: to update the",
    "grammars, change the version in `tools/grammars/package.json`, then run",
    "",
    "```bash",
    "cd textmate/tools/grammars && npm install && npm run copy",
    "```",
    "",
    "and regenerate the goldens (see README.md). The script refuses a grammar whose licence isn't permissive or",
    "checked.",
    "",
    "| Name | Scope | Licence | Source | Why shipped |",
    "|---|---|---|---|---|",
    ...rows,
    "",
    "Left out, though a shipped grammar embeds it; what it colours stays plain:",
    "",
    "| Name | Why |",
    "|---|---|",
    ...leftOut,
    "",
    "The licences that tm-grammars doesn't record were read in each repository, at the commit the grammar was taken",
    "from:",
    "",
    ...Object.entries(CHECKED).map(([name, { license, file }]) => `- ${name}: ${license}, from its \`${file}\`.`),
    "",
  ].join("\n");
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
