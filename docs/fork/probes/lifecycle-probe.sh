#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only

# Fork-only. Reproduces the findings of docs/fork/worktree-lifecycle.md about merges and rebases that the git CLI
# starts and JGit finishes, against throwaway repos created under $TMPDIR. Never touches a real repository.
#
# Requirements: git, JAVA_HOME pointing at a JDK 25 (17+ works for the probe itself), and JGit in the Gradle cache
# (run ./gradlew build once). Set KEEP=1 to keep the temp repos for inspection.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROBE="$ROOT/docs/fork/probes/LifecycleProbe.java"
JAVA="${JAVA_HOME:?set JAVA_HOME to a JDK 25}/bin/java"
JGIT_VERSION="$(sed -n 's/^jgit = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
jar() { find "$CACHE/$1" -name "$2" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1; }
CP="$(jar org.eclipse.jgit/org.eclipse.jgit "org.eclipse.jgit-$JGIT_VERSION.jar")"
CP="$CP:$(jar org.slf4j/slf4j-api 'slf4j-api-*.jar')"
CP="$CP:$(jar com.googlecode.javaewah/JavaEWAH 'JavaEWAH-*.jar')"
CP="$CP:$(jar commons-codec/commons-codec 'commons-codec-*.jar')"

LAB="$(mktemp -d "${TMPDIR:-/tmp}/leaf-lifecycle-probe.XXXXXX")"
if [[ "${KEEP:-0}" == "1" ]]; then echo "Keeping temp repos in $LAB"; else trap 'rm -rf "$LAB"' EXIT; fi
mkdir -p "$LAB/home/.config"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 XDG_CONFIG_HOME="$LAB/home/.config" GIT_EDITOR=true

probe() { "$JAVA" -Duser.home="$LAB/home" -cp "$CP" "$PROBE" "$@" 2>&1 | grep -v '^SLF4J' || true; }
section() { printf '\n=== %s\n' "$1"; }
new_repo() {
  git init -q -b main "$1"
  git -C "$1" config user.email probe@example.invalid
  git -C "$1" config user.name probe
  printf 'one\ntwo\nthree\n' > "$1/a.txt"
  git -C "$1" add . && git -C "$1" commit -qm init
}
gitdir() { (cd "$1" && cd "$(git rev-parse --git-dir)" && pwd); }

echo "JGit $JGIT_VERSION, $(git --version)"

# Repository: main has a.txt; 'agent' (in a linked worktree) changes line two, main changes line two too.
setup() {
  M="$LAB/$1"; W="$LAB/$1-agent"
  new_repo "$M"
  git -C "$M" worktree add -q -b agent "$W"
  printf 'one\nAGENT\nthree\n' > "$W/a.txt"; git -C "$W" commit -qam "agent: change two"
  echo b > "$W/b.txt"; git -C "$W" add b.txt; git -C "$W" commit -qm "agent: add b"
  printf 'one\nMAIN\nthree\n' > "$M/a.txt"; git -C "$M" commit -qam "main: change two"
  WG="$(gitdir "$W")"; MG="$(gitdir "$M")"
}

section "CLI rebase stopped at a conflict in the linked worktree, JGit continues"
setup r1
git -C "$W" rebase main >/dev/null 2>&1 || true
probe state "$WG"
ls "$WG/rebase-merge" | paste -sd ' ' -
printf 'one\nRESOLVED\nthree\n' > "$W/a.txt"; git -C "$W" add a.txt
probe rebase-continue "$WG"
git -C "$W" status --short --branch | head -3
git -C "$W" log --oneline -4 | cat
echo "git sees: $(git -C "$W" rev-parse --git-path rebase-merge | xargs -I{} sh -c 'test -d {} && echo still-rebasing || echo done')"

section "CLI rebase stopped at a conflict, JGit aborts"
setup r2
before="$(git -C "$W" rev-parse HEAD)"
git -C "$W" rebase main >/dev/null 2>&1 || true
probe rebase-abort "$WG"
echo "back at the old head: $([[ "$(git -C "$W" rev-parse HEAD)" == "$before" ]] && echo yes || echo no), branch: $(git -C "$W" branch --show-current)"
git -C "$W" status --short | head -3

section "CLI rebase stopped at a conflict, JGit skips"
setup r3
git -C "$W" rebase main >/dev/null 2>&1 || true
probe rebase-skip "$WG"
git -C "$W" log --oneline -3 | cat
echo "branch: $(git -C "$W" branch --show-current)"

section "CLI merge --squash in the main worktree, then a JGit commit"
setup s1
git -C "$M" checkout -q -- . ; git -C "$M" reset -q --hard HEAD~1   # main without its conflicting commit
git -C "$M" merge --squash agent >/dev/null 2>&1 || true
probe state "$MG"
head -3 "$MG/SQUASH_MSG"
probe commit "$MG" "Squashed agent"
echo "SQUASH_MSG left: $([[ -f "$MG/SQUASH_MSG" ]] && echo yes || echo no)"

section "CLI merge --squash with a conflict"
setup s2
git -C "$M" merge --squash agent >/dev/null 2>&1 || true
probe state "$MG"
git -C "$M" status --short | cat

section "CLI merge --no-ff --no-commit, then a JGit commit"
setup m1
git -C "$M" reset -q --hard HEAD~1
git -C "$M" merge --no-ff --no-commit agent >/dev/null 2>&1 || true
probe state "$MG"
head -1 "$MG/MERGE_MSG"
probe commit "$MG" "Merge agent"

section "JGit squash, for comparison"
setup s3
git -C "$M" reset -q --hard HEAD~1
probe squash "$MG" agent

section "git merge-tree tells a squash-merged branch from an unmerged one, which ancestry can't"
setup t1
git -C "$M" reset -q --hard HEAD~1
merged() {
  local anc=no same=no tree
  git -C "$M" merge-base --is-ancestor agent main && anc=yes
  tree="$(git -C "$M" merge-tree --write-tree main agent 2>/dev/null | head -1 || true)"
  [[ "$tree" == "$(git -C "$M" rev-parse 'main^{tree}')" ]] && same=yes
  echo "ancestor=$anc nothing-to-merge=$same commits-not-in-main=$(git -C "$M" rev-list --count main..agent)"
}
echo "unmerged:         $(merged)"
git -C "$M" merge -q --squash agent >/dev/null; git -C "$M" commit -qm "Squashed agent"
echo "after the squash: $(merged)"
echo more > "$W/more.txt"; git -C "$W" add more.txt; git -C "$W" commit -qm more
echo "one more commit:  $(merged)"

section "git worktree remove: ignored files, untracked files, locks, a missing folder"
setup w1
printf 'build/\n.env\n' > "$M/.gitignore"; git -C "$M" add .gitignore; git -C "$M" commit -qm ignore
git -C "$M" worktree add -q -b ignored "$LAB/w1-ignored"
mkdir -p "$LAB/w1-ignored/build"; echo out > "$LAB/w1-ignored/build/out.o"; echo S=1 > "$LAB/w1-ignored/.env"
git -C "$M" worktree remove "$LAB/w1-ignored" && echo "only ignored files: removed without --force"
echo draft > "$W/draft.txt"
git -C "$M" worktree remove "$W" 2>&1 | sed 's/^/untracked file: /' || true
rm "$W/draft.txt"
git -C "$M" worktree lock --reason "agent running" "$W"
git -C "$M" worktree remove --force "$W" 2>&1 | head -1 | sed 's/^/locked, one --force: /' || true
git -C "$M" worktree unlock "$W" && git -C "$M" worktree remove "$W" && echo "locked: unlock, then remove, works"
git -C "$M" worktree add -q -b gone "$LAB/w1-gone"; rm -rf "$LAB/w1-gone"
git -C "$M" worktree prune --dry-run -v 2>&1 | sed 's/^/prune --dry-run -v: /'
git -C "$M" worktree remove "$LAB/w1-gone" && echo "a missing folder: removed without --force"
