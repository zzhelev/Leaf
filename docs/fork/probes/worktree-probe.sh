#!/usr/bin/env bash
# Fork-only. Reproduces the linked-worktree findings in docs/fork/architecture-notes.md against throwaway repos
# created under $TMPDIR. Never touches a real repository.
#
# Requirements: git, JAVA_HOME pointing at a JDK 25 (17+ works for the probe itself), and JGit in the Gradle cache
# (run ./gradlew build once). Set KEEP=1 to keep the temp repos for inspection.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROBE="$ROOT/docs/fork/probes/WorktreeProbe.java"
JAVA="${JAVA_HOME:?set JAVA_HOME to a JDK 25}/bin/java"
JGIT_VERSION="$(sed -n 's/^jgit = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

jar() { find "$CACHE/$1" -name "$2" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1; }
CP="$(jar org.eclipse.jgit/org.eclipse.jgit "org.eclipse.jgit-$JGIT_VERSION.jar")"
CP="$CP:$(jar org.slf4j/slf4j-api 'slf4j-api-*.jar')"
CP="$CP:$(jar com.googlecode.javaewah/JavaEWAH 'JavaEWAH-*.jar')"
CP="$CP:$(jar commons-codec/commons-codec 'commons-codec-*.jar')"

LAB="$(mktemp -d "${TMPDIR:-/tmp}/gitnuro-worktree-probe.XXXXXX")"
if [[ "${KEEP:-0}" == "1" ]]; then echo "Keeping temp repos in $LAB"; else trap 'rm -rf "$LAB"' EXIT; fi

probe() { "$JAVA" -cp "$CP" "$PROBE" "$@" 2>&1 | grep -v '^SLF4J' || true; }
section() { printf '\n=== %s\n' "$1"; }
days_ago() { date -v-"$1"d +%Y%m%d%H%M 2>/dev/null || date -d "$1 days ago" +%Y%m%d%H%M; }
new_repo() {
  git init -q -b main "$1"
  git -C "$1" config user.email probe@example.invalid
  git -C "$1" config user.name probe
  echo a > "$1/a.txt"
  git -C "$1" add . && git -C "$1" commit -qm init
}
obj_type() { git -C "$1" cat-file -t "$2" 2>/dev/null || echo MISSING; }

echo "JGit $JGIT_VERSION, $(git --version), java $("$JAVA" -version 2>&1 | head -1)"

M="$LAB/open/main-repo"
new_repo "$M"
git -C "$M" worktree add -q ../wt-test -b test
git -C "$M" worktree add -q .claude/worktrees/agent1 -b agent1

section "1a. Gitnuro open flow, sibling worktree ../wt-test"
probe gitnuro-open "$LAB/open/wt-test"
section "1b. Gitnuro open flow, nested worktree .claude/worktrees/agent1"
probe gitnuro-open "$M/.claude/worktrees/agent1"
section "1c. Plain JGit Git.open(<linked worktree dir>)"
probe git-open "$LAB/open/wt-test"

section "2. Checkout of 'test' (checked out in wt-test) from the main worktree"
echo "git CLI: $(git -C "$M" checkout test 2>&1 || true)"
echo "JGit:    $(probe checkout "$M/.git" test)"
git -C "$M" worktree list --porcelain | grep '^branch'
git -C "$M" checkout -q main

section "3. Delete of 'agent1' (checked out in .claude/worktrees/agent1) from the main worktree"
echo "git CLI: $(git -C "$M" branch -D agent1 2>&1 || true)"
echo "JGit:    $(probe delete "$M/.git" refs/heads/agent1)"
echo "agent1 worktree now: $(git -C "$M/.claude/worktrees/agent1" status 2>&1 | sed -n 3p)"

# A linked worktree with a detached-HEAD commit and a staged-only blob: objects only that worktree references.
gc_lab() {
  new_repo "$1/main-repo"
  git -C "$1/main-repo" worktree add -q ../wt
  git -C "$1/wt" checkout -q --detach
  echo d > "$1/wt/d.txt" && git -C "$1/wt" add d.txt && git -C "$1/wt" commit -qm "detached work"
  echo s > "$1/wt/s.txt" && git -C "$1/wt" add s.txt
  DETACHED="$(git -C "$1/wt" rev-parse HEAD)"
  STAGED="$(git -C "$1/wt" rev-parse :s.txt)"
}
report() { echo "  $2: detached commit $(obj_type "$1/wt" "$DETACHED"), staged blob $(obj_type "$1/wt" "$STAGED")"; }

section "4a. gc with prune expiry 'now', run from the main worktree"
gc_lab "$LAB/gc-cli"; git -C "$LAB/gc-cli/main-repo" gc -q --prune=now; report "$LAB/gc-cli" "git gc --prune=now"
gc_lab "$LAB/gc-jgit"; probe gc-now "$LAB/gc-jgit/main-repo/.git" > /dev/null; report "$LAB/gc-jgit" "JGit gc (expire now)"

section "4b. JGit gc with default settings (2-week prune expiry), objects aged by touching mtimes"
L="$LAB/gc-default"; gc_lab "$L"
git -C "$L/main-repo" repack -q -a -d
find "$L/main-repo/.git/objects" -type f -exec touch -t "$(days_ago 21)" {} +
probe gc-default "$L/main-repo/.git" > /dev/null; report "$L" "1st default GC, 21-day-old pack"
find "$L/main-repo/.git/objects" -type f -path '*/objects/??/*' -exec touch -t "$(days_ago 15)" {} +
probe gc-default "$L/main-repo/.git" > /dev/null; report "$L" "2nd default GC, loosened objects 15 days old"
