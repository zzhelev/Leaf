// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

// Writes the tokens that vscode-textmate, the engine VS Code runs, gives for each sample of the shipped grammars, for
// ShippedGrammarsConformanceTest to compare with this module's.
// Usage: cd textmate/tools/conformance && npm ci && npm run generate [-- <samples folder> <expected folder>]
// Samples are in <samples folder>/<grammar name>/, and the tokens of each go to
// <expected folder>/<grammar name>/<file>.tokens, one line per token: <line>:<start>-<end> <scopes>, the offsets in
// UTF-16 units, as Kotlin's strings count them.
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "fs";
import { createRequire } from "module";
import { join, resolve } from "path";

const require = createRequire(import.meta.url);
const vsctm = require("vscode-textmate");
const oniguruma = require("vscode-oniguruma");

const MODULE = join(import.meta.dirname, "..", "..");
const GRAMMARS = join(MODULE, "src", "main", "resources", "grammars");
const SAMPLES = resolve(process.argv[2] ?? join(MODULE, "src", "test", "resources", "conformance", "samples"));
const EXPECTED = resolve(process.argv[3] ?? join(MODULE, "src", "test", "resources", "conformance", "expected"));

await oniguruma.loadWASM(readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm")).buffer);
const index = JSON.parse(readFileSync(join(GRAMMARS, "index.json"), "utf8"));
const byScope = new Map(index.map((g) => [g.scopeName, g]));
const byName = new Map(index.map((g) => [g.name, g]));

const registry = new vsctm.Registry({
  onigLib: Promise.resolve({
    createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
    createOnigString: (s) => new oniguruma.OnigString(s),
  }),
  loadGrammar: async (scope) => {
    const entry = byScope.get(scope);
    if (!entry) return null;
    const path = join(GRAMMARS, entry.file);
    return vsctm.parseRawGrammar(readFileSync(path, "utf8"), path);
  },
  // As VS Code finds them: the grammars whose injectTo names the scope.
  getInjections: (scope) => index.filter((g) => g.injectTo.includes(scope)).map((g) => g.scopeName),
});

const version = (name) => JSON.parse(readFileSync(require.resolve(`${name}/package.json`), "utf8")).version;
const header = `# vscode-textmate ${version("vscode-textmate")}, vscode-oniguruma ${version("vscode-oniguruma")}`;

rmSync(EXPECTED, { recursive: true, force: true });
for (const name of readdirSync(SAMPLES).sort()) {
  const entry = byName.get(name);
  if (!entry) throw new Error(`No shipped grammar named ${name}`);
  const grammar = await registry.loadGrammar(entry.scopeName);
  mkdirSync(join(EXPECTED, name), { recursive: true });
  for (const file of readdirSync(join(SAMPLES, name)).sort()) {
    const lines = readFileSync(join(SAMPLES, name, file), "utf8").split(/\r?\n/);
    if (lines.at(-1) === "") lines.pop();
    const out = [header];
    let state = vsctm.INITIAL;
    lines.forEach((line, i) => {
      const result = grammar.tokenizeLine(line, state);
      for (const t of result.tokens) {
        out.push(`${i}:${t.startIndex}-${Math.min(t.endIndex, line.length)} ${t.scopes.join(" ")}`);
      }
      state = result.ruleStack;
    });
    writeFileSync(join(EXPECTED, name, `${file}.tokens`), out.join("\n") + "\n");
  }
  console.log(name);
}
