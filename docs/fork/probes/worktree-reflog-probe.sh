#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only

# Fork-only. Reproduces the linked-worktree HEAD reflog findings in docs/fork/architecture-notes.md against throwaway
# repos created under $TMPDIR. Never touches a real repository.
#
# Requirements: git, JAVA_HOME pointing at a JDK 25 (17+ works for the probe itself), and JGit in the Gradle cache
# (run ./gradlew build once). Set KEEP=1 to keep the temp repos for inspection.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROBE="$ROOT/docs/fork/probes/WorktreeReflogProbe.java"
JAVA="${JAVA_HOME:?set JAVA_HOME to a JDK 25}/bin/java"
JGIT_VERSION="$(sed -n 's/^jgit = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

jar() { find "$CACHE/$1" -name "$2" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1; }
CP="$(jar org.eclipse.jgit/org.eclipse.jgit "org.eclipse.jgit-$JGIT_VERSION.jar")"
CP="$CP:$(jar org.slf4j/slf4j-api 'slf4j-api-*.jar')"
CP="$CP:$(jar com.googlecode.javaewah/JavaEWAH 'JavaEWAH-*.jar')"
CP="$CP:$(jar commons-codec/commons-codec 'commons-codec-*.jar')"

LAB="$(mktemp -d "${TMPDIR:-/tmp}/leaf-reflog-probe.XXXXXX")"
if [[ "${KEEP:-0}" == "1" ]]; then echo "Keeping temp repos in $LAB"; else trap 'rm -rf "$LAB"' EXIT; fi

# Keep git and JGit away from the developer's config: no global or system config, and a home of our own.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 XDG_CONFIG_HOME="$LAB/home/.config"
mkdir -p "$XDG_CONFIG_HOME"

probe() { "$JAVA" -Duser.home="$LAB/home" -cp "$CP" "$PROBE" "$@" 2>&1 | grep -v '^SLF4J' || true; }
section() { printf '\n=== %s\n' "$1"; }
new_repo() {
  git init -q -b main "$1"
  git -C "$1" config user.email probe@example.invalid
  git -C "$1" config user.name probe
  echo a > "$1/a.txt"
  git -C "$1" add . && git -C "$1" commit -qm init
}
lines() { if [[ -f "$1" ]]; then wc -l < "$1" | tr -d ' '; else echo 0; fi; }
# The messages of the reflog lines after the first $2 lines of file $1, joined with " | ".
added() { if [[ -f "$1" ]]; then tail -n +"$(($2 + 1))" "$1" | cut -f2 | paste -sd '|' - | sed 's/|/ | /g'; fi; }
obj_type() { git -C "$1" cat-file -t "$2" 2>/dev/null || echo MISSING; }

# Runs a probe op in the linked worktree's tab and reports which HEAD reflog got its entries.
run() {
  local label="$1"; shift
  local m w out
  m="$(lines "$M_LOG")"; w="$(lines "$W_LOG")"
  out="$(probe "$@")"
  printf '%-34s main: %-60s linked: %s\n' "$label" "$(added "$M_LOG" "$m")" "$(added "$W_LOG" "$w")"
  if [[ -n "$out" ]]; then echo "    $out"; fi
}

echo "JGit $JGIT_VERSION, $(git --version), java $("$JAVA" -version 2>&1 | head -1)"

# c2 is the main worktree, c2-wt a linked worktree on 'agent'. 'feature' and 'other' each have one commit on main.
M="$LAB/c2"; W="$LAB/c2-wt"
new_repo "$M"
git -C "$M" checkout -q -b feature && echo f > "$M/f.txt" && git -C "$M" add . && git -C "$M" commit -qm "feature commit"
git -C "$M" checkout -q -b other main && echo o > "$M/o.txt" && git -C "$M" add . && git -C "$M" commit -qm "other commit"
git -C "$M" checkout -q main
git -C "$M" worktree add -q -b agent ../c2-wt
WG="$M/.git/worktrees/c2-wt" # what a Leaf tab on c2-wt has as its repositoryPath
M_LOG="$M/.git/logs/HEAD"; W_LOG="$WG/logs/HEAD"

section "1. Baseline: the git CLI in the linked worktree"
m="$(lines "$M_LOG")"; w="$(lines "$W_LOG")"
git -C "$W" commit -q --allow-empty -m "cli commit"
printf '%-34s main: %-60s linked: %s\n' "git commit" "$(added "$M_LOG" "$m")" "$(added "$W_LOG" "$w")"

MAIN_PREV="$(git -C "$M" log -1 --format='%h %s' 'HEAD@{1}')"

section "2. JGit in the linked worktree's tab: where each HEAD reflog entry goes"
run "commit" commit "$WG" "wt commit 1"
run "amend" amend "$WG" "wt commit 1 amended"
run "create branch topic" create-branch "$WG" topic
run "checkout agent" checkout "$WG" agent
run "checkout commit (detach)" checkout-commit "$WG" agent~1
run "checkout agent" checkout "$WG" agent
run "commit" commit "$WG" "wt commit 2"
run "reset --hard HEAD~1" reset "$WG" hard HEAD~1
run "reset --hard HEAD (merge abort)" reset "$WG" hard HEAD
run "merge feature --no-ff" merge "$WG" feature no-ff
run "cherry-pick other" cherry-pick "$WG" other
run "revert HEAD" revert "$WG" HEAD
run "checkout topic" checkout "$WG" topic
run "rebase onto feature" rebase "$WG" feature
echo x >> "$W/a.txt"
run "stash" stash "$WG"
run "rename topic -> topic2 (current)" rename "$WG" topic topic2
run "checkout agent" checkout "$WG" agent
run "RefUpdate.link HEAD to topic2" link "$WG" refs/heads/topic2
run "RefUpdate.link HEAD to agent" link "$WG" refs/heads/agent

section "3. The HEAD reflog as JGit reads it (GetRefDatesGitAction's last-checkout dates)"
echo "Linked worktree's tab:"; probe reflog "$WG"
echo "Main worktree's tab:"; probe reflog "$M/.git"

section "4. What git shows afterwards"
echo "git -C c2-wt reflog (the linked worktree, where these entries belong):"
git -C "$W" reflog -n 5 --format='  %gd %gs'
echo "git -C c2 reflog (the main worktree, which stayed on main):"
git -C "$M" reflog -n 5 --format='  %gd %gs'
echo "c2:    HEAD@{1} = $(git -C "$M" log -1 --format='%h %s' 'HEAD@{1}') (before section 2: $MAIN_PREV)"
echo "c2-wt: @{-1} = $(git -C "$W" rev-parse --abbrev-ref '@{-1}' 2>/dev/null | grep -v '^@' || echo "none: no checkout in its reflog")"
echo "c2:    @{-1} = $(git -C "$M" rev-parse --abbrev-ref '@{-1}' 2>&1), so git checkout - in c2:"
echo "       $(git -C "$M" checkout - 2>&1 | tail -1)"

section "5. Reachability: commits made on a detached HEAD in the linked worktree, then left"
G="$LAB/gc"; new_repo "$G/main-repo"; git -C "$G/main-repo" worktree add -q ../wt
GG="$G/main-repo/.git/worktrees/wt"
probe checkout-commit "$GG" HEAD > /dev/null
probe commit "$GG" "jgit detached work" > /dev/null
D="$(git -C "$G/wt" rev-parse HEAD)"
probe checkout "$GG" wt > /dev/null
git -C "$G/wt" checkout -q --detach && git -C "$G/wt" commit -q --allow-empty -m "cli detached work"
C="$(git -C "$G/wt" rev-parse HEAD)"
git -C "$G/wt" checkout -q wt
count() { grep -c "$1" "$2" || true; }
echo "HEAD reflog lines naming the JGit commit: main $(count "$D" "$G/main-repo/.git/logs/HEAD"), linked $(count "$D" "$GG/logs/HEAD")"
echo "HEAD reflog lines naming the CLI commit:  main $(count "$C" "$G/main-repo/.git/logs/HEAD"), linked $(count "$C" "$GG/logs/HEAD")"
# The copy's worktree links still point into $G, so it's only read through its main worktree, which shares the objects.
cp -R "$G" "$G-jgit"
git -C "$G/main-repo" gc -q --prune=now
echo "  git gc --prune=now from the main worktree: JGit commit $(obj_type "$G/main-repo" "$D"), CLI commit $(obj_type "$G/main-repo" "$C")"
probe gc-now "$G-jgit/main-repo/.git/worktrees/wt" > /dev/null
echo "  JGit gc (expire now) from the linked tab:  JGit commit $(obj_type "$G-jgit/main-repo" "$D"), CLI commit $(obj_type "$G-jgit/main-repo" "$C")"

section "6. A bare repository with linked worktrees (the layout some agent setups use)"
B="$LAB/bare"; new_repo "$B/seed"; git clone -q --bare "$B/seed" "$B/repo.git"
git -C "$B/repo.git" worktree add -q -b agent ../wt
BG="$B/repo.git/worktrees/wt"
probe commit "$BG" "wt commit" > /dev/null
echo "repo.git/logs/HEAD:              $(added "$B/repo.git/logs/HEAD" 0)"
echo "repo.git/worktrees/wt/logs/HEAD: $(added "$BG/logs/HEAD" 0)"

section "7. Side check: ORIG_HEAD after a squash in the linked worktree"
git -C "$W" checkout -q -b sq
echo 1 > "$W/s1.txt" && git -C "$W" add s1.txt && git -C "$W" commit -qm s1
echo 2 > "$W/s2.txt" && git -C "$W" add s2.txt && git -C "$W" commit -qm s2
BEFORE="$(git -C "$W" rev-parse --short HEAD)"
git -C "$M" update-ref ORIG_HEAD other # the main worktree's own ORIG_HEAD, as a reset there would leave it
probe squash "$WG" sq~2
echo "c2-wt ORIG_HEAD after the squash: $(git -C "$W" rev-parse --short ORIG_HEAD) (git would leave $BEFORE," \
  "the tip before the rebase; c2's ORIG_HEAD is $(git -C "$M" rev-parse --short ORIG_HEAD))"
