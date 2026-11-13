#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only
#
# Fork-only. Reproduces the findings in docs/fork/status-renames.md: what `git status`, `git diff --cached -M`,
# `git diff -M` and JGit 7.7 report for a moved file, and what unstaging, discarding, a hunk patch, blame and file
# history do with one. Works on throwaway repos under $TMPDIR; never touches a real repository.
#
# Requirements: git, JAVA_HOME pointing at a JDK 25, and JGit in the Gradle cache (run ./gradlew build once).
# Set KEEP=1 to keep the temp repos for inspection.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROBE="$ROOT/docs/fork/probes/StatusRenamesProbe.java"
JAVA="${JAVA_HOME:?set JAVA_HOME to a JDK 25}/bin/java"
JGIT_VERSION="$(sed -n 's/^jgit = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

jar() { find "$CACHE/$1" -name "$2" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1; }
CP="$(jar org.eclipse.jgit/org.eclipse.jgit "org.eclipse.jgit-$JGIT_VERSION.jar")"
CP="$CP:$(jar org.slf4j/slf4j-api 'slf4j-api-*.jar')"
CP="$CP:$(jar com.googlecode.javaewah/JavaEWAH 'JavaEWAH-*.jar')"
CP="$CP:$(jar commons-codec/commons-codec 'commons-codec-*.jar')"

LAB="$(mktemp -d "${TMPDIR:-/tmp}/status-renames-probe.XXXXXX")"
if [[ "${KEEP:-0}" == "1" ]]; then echo "Keeping temp repos in $LAB"; else trap 'rm -rf "$LAB"' EXIT; fi

# git reads no user or system config, and makes no identity up
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_AUTHOR_NAME=probe GIT_AUTHOR_EMAIL=probe@example.invalid
export GIT_COMMITTER_NAME=probe GIT_COMMITTER_EMAIL=probe@example.invalid GIT_EDITOR=true

probe() { "$JAVA" -cp "$CP" "$PROBE" "$@" 2>&1 | grep -v '^SLF4J' || true; }
section() { printf '\n=== %s\n' "$1"; }
lines() { for i in $(seq 1 20); do echo "$1 line $i"; done; }

# A repository with a.txt (20 lines), c.txt (20 other lines) and an empty file, committed
new_repo() {
  local dir="$LAB/$1"
  git init -q -b main "$dir"
  lines alpha > "$dir/a.txt"
  lines gamma > "$dir/c.txt"
  : > "$dir/empty.txt"
  git -C "$dir" add . && git -C "$dir" commit -qm init
  echo "$dir"
}

show() {
  local dir="$1"
  echo "  git status --short:"; git -C "$dir" status --short | sed 's/^/    /'
  echo "  git status --porcelain=v2:"; git -C "$dir" status --porcelain=v2 | sed 's/^/    /'
  echo "  git diff --cached -M --name-status:"; git -C "$dir" diff --cached -M --name-status | sed 's/^/    /'
  echo "  git diff -M --name-status:"; git -C "$dir" diff -M --name-status | sed 's/^/    /'
  probe status "$dir"
  echo "  jgit staged (HEAD -> index, renames at 50):"; probe staged "$dir" | sed 's/^/  /'
  echo "  jgit unstaged (index -> working tree, renames at 50):"; probe unstaged "$dir" | sed 's/^/  /'
}

echo "JGit $JGIT_VERSION, $(git --version), java $("$JAVA" -version 2>&1 | head -1)"

section "1. A plain move: git mv a.txt b.txt"
R="$(new_repo plain)"; git -C "$R" mv a.txt b.txt; show "$R"

section "2. A move with edits, staged: git mv, change 3 of 20 lines, git add"
R="$(new_repo edited)"; git -C "$R" mv a.txt b.txt
sed -i '' -e 's/alpha line 1$/changed 1/' -e 's/alpha line 2$/changed 2/' -e 's/alpha line 3$/changed 3/' "$R/b.txt"
git -C "$R" add b.txt; show "$R"

section "3a. A move outside git: mv a.txt b.txt (new path untracked)"
R="$(new_repo untracked)"; mv "$R/a.txt" "$R/b.txt"; show "$R"

section "3b. The new path staged alone: mv, then git add b.txt"
R="$(new_repo halfadded)"; mv "$R/a.txt" "$R/b.txt"; git -C "$R" add b.txt; show "$R"

section "3c. The old path's deletion staged alone: git rm a.txt, b.txt untracked"
R="$(new_repo halfremoved)"; cp "$R/a.txt" "$R/b.txt"; git -C "$R" rm -q a.txt; show "$R"

section "4. Intent-to-add: mv, then git add -N b.txt"
R="$(new_repo ita)"; mv "$R/a.txt" "$R/b.txt"; git -C "$R" add -N b.txt; show "$R"

section "4b. Intent-to-add without a move: a new file, git add -N, then JGit's commit and git's commit"
R="$(new_repo itanew)"; lines new > "$R/new.txt"; echo "x" >> "$R/c.txt"; git -C "$R" add -N new.txt; git -C "$R" add c.txt; show "$R"
probe commit "$R" "jgit commit"
echo "  what JGit's commit took:"; git -C "$R" show --stat --format= HEAD | sed 's/^/    /'
git -C "$R" cat-file -p HEAD:new.txt 2>/dev/null | head -1 | sed 's/^/    new.txt in the commit starts with: /' || true
git -C "$R" status --short | sed 's/^/    then: /'
R="$(new_repo itanewgit)"; lines new > "$R/new.txt"; echo "x" >> "$R/c.txt"; git -C "$R" add -N new.txt; git -C "$R" add c.txt
git -C "$R" commit -qm "git commit"
echo "  what git's commit took:"; git -C "$R" show --stat --format= HEAD | sed 's/^/    /'

section "5. A staged move edited again: git mv, then append a line"
R="$(new_repo stagededited)"; git -C "$R" mv a.txt b.txt; echo "one more" >> "$R/b.txt"; show "$R"

section "5b. A staged move whose new file is then deleted: git mv, rm b.txt"
R="$(new_repo stageddeleted)"; git -C "$R" mv a.txt b.txt; rm "$R/b.txt"; show "$R"

section "6a. A conflict: theirs renames a.txt to b.txt, ours changes the same line of a.txt"
R="$(new_repo conflict)"
git -C "$R" checkout -qb theirs; git -C "$R" mv a.txt b.txt
sed -i '' 's/alpha line 5$/theirs 5/' "$R/b.txt"; git -C "$R" commit -qam "theirs renames"
git -C "$R" checkout -q main; sed -i '' 's/alpha line 5$/ours 5/' "$R/a.txt"; git -C "$R" commit -qam "ours edits"
git -C "$R" merge -q theirs >/dev/null 2>&1 || true; show "$R"

section "6b. A conflict next to a clean rename: theirs renames c.txt to d.txt, both change a.txt's line 5"
R="$(new_repo conflictnext)"
git -C "$R" checkout -qb theirs; git -C "$R" mv c.txt d.txt; sed -i '' 's/alpha line 5$/theirs 5/' "$R/a.txt"
git -C "$R" commit -qam "theirs"
git -C "$R" checkout -q main; sed -i '' 's/alpha line 5$/ours 5/' "$R/a.txt"; git -C "$R" commit -qam "ours"
git -C "$R" merge -q theirs >/dev/null 2>&1 || true; show "$R"

section "6c. rename/delete: theirs renames a.txt to b.txt, ours deletes a.txt"
R="$(new_repo renamedelete)"
git -C "$R" checkout -qb theirs; git -C "$R" mv a.txt b.txt; git -C "$R" commit -qm "theirs renames"
git -C "$R" checkout -q main; git -C "$R" rm -q a.txt; git -C "$R" commit -qm "ours deletes"
git -C "$R" merge -q theirs >/dev/null 2>&1 || true; show "$R"

section "7. A copy: cp a.txt copy.txt, git add (a.txt kept)"
R="$(new_repo copy)"; cp "$R/a.txt" "$R/copy.txt"; git -C "$R" add copy.txt; show "$R"

section "8. An empty file moved: git mv empty.txt empty2.txt"
R="$(new_repo empty)"; git -C "$R" mv empty.txt empty2.txt; show "$R"

section "9. Two files with the same content, both moved"
R="$(new_repo twins)"; cp "$R/a.txt" "$R/a2.txt"; git -C "$R" add a2.txt; git -C "$R" commit -qm twin
git -C "$R" mv a.txt x/a.txt 2>/dev/null || { mkdir "$R/x"; git -C "$R" mv a.txt x/a.txt; }
git -C "$R" mv a2.txt x/a2.txt; show "$R"

section "10. A move with most lines changed (below 50% alike)"
R="$(new_repo rewritten)"; git -C "$R" mv a.txt b.txt; lines other | head -15 > "$R/b.txt"; lines alpha | head -5 >> "$R/b.txt"
git -C "$R" add b.txt; show "$R"

section "11. status.renames=false, then diff.renames=false (git mv a.txt b.txt)"
R="$(new_repo configs)"; git -C "$R" mv a.txt b.txt
echo "  status.renames=false:"; git -C "$R" -c status.renames=false status --short | sed 's/^/    /'
echo "  diff.renames=false:"; git -C "$R" -c diff.renames=false status --short | sed 's/^/    /'
echo "  status.renames=copies with a copy staged:"; cp "$R/c.txt" "$R/c2.txt"; git -C "$R" add c2.txt
git -C "$R" -c status.renames=copies status --short | sed 's/^/    /'

section "12. A folder moved: git mv src lib (3 files)"
R="$(new_repo folder)"; mkdir "$R/src"; for f in one two three; do lines "$f" > "$R/src/$f.txt"; done
git -C "$R" add src && git -C "$R" commit -qm src; git -C "$R" mv src lib; show "$R"

section "13. Unstaging a staged move (case 1): the new path alone, then both paths"
R="$(new_repo unstage1)"; git -C "$R" mv a.txt b.txt; git -C "$R" restore --staged b.txt
echo "  after git restore --staged b.txt:"; git -C "$R" status --short | sed 's/^/    /'
R="$(new_repo unstage2)"; git -C "$R" mv a.txt b.txt; git -C "$R" restore --staged a.txt b.txt
echo "  after git restore --staged a.txt b.txt:"; git -C "$R" status --short | sed 's/^/    /'
R="$(new_repo unstage3)"; git -C "$R" mv a.txt b.txt; probe reset "$R" b.txt
echo "  after JGit reset b.txt:"; git -C "$R" status --short | sed 's/^/    /'
R="$(new_repo unstage4)"; git -C "$R" mv a.txt b.txt; probe reset "$R" a.txt b.txt
echo "  after JGit reset a.txt b.txt:"; git -C "$R" status --short | sed 's/^/    /'

section "14. Discarding a staged move (case 5, edited again): Leaf's reset + checkout, and git restore -SW"
R="$(new_repo discard1)"; git -C "$R" mv a.txt b.txt; echo "one more" >> "$R/b.txt"
probe reset "$R" b.txt; probe checkout "$R" b.txt
echo "  after Leaf's discard of b.txt as it is today (JGit reset + checkout b.txt):"; git -C "$R" status --short | sed 's/^/    /'
R="$(new_repo discard2)"; git -C "$R" mv a.txt b.txt; echo "one more" >> "$R/b.txt"
probe reset "$R" a.txt b.txt; probe checkout "$R" a.txt b.txt
echo "  after JGit reset + checkout of a.txt and b.txt:"; git -C "$R" status --short | sed 's/^/    /'
ls "$R" | sed 's/^/    on disk: /'
R="$(new_repo discard3)"; git -C "$R" mv a.txt b.txt; echo "one more" >> "$R/b.txt"
git -C "$R" restore --source=HEAD --staged --worktree -- a.txt b.txt
echo "  after git restore --source=HEAD --staged --worktree -- a.txt b.txt:"; git -C "$R" status --short | sed 's/^/    /'
ls "$R" | sed 's/^/    on disk: /'

section "15. Unstaging one hunk of a staged move with edits (case 2), patch on the new path, git apply --cached -R"
R="$(new_repo hunk)"; git -C "$R" mv a.txt b.txt
sed -i '' -e 's/alpha line 2$/changed 2/' -e 's/alpha line 18$/changed 18/' "$R/b.txt"; git -C "$R" add b.txt
cat > "$LAB/hunk.patch" <<'PATCH'
diff --git a/b.txt b/b.txt
--- a/b.txt
+++ b/b.txt
@@ -1,5 +1,5 @@
 alpha line 1
-alpha line 2
+changed 2
 alpha line 3
 alpha line 4
 alpha line 5
PATCH
git -C "$R" apply --cached -R --whitespace=nowarn "$LAB/hunk.patch" && echo "  applied"
git -C "$R" diff --cached -M --stat | sed 's/^/    /'
git -C "$R" status --short | sed 's/^/    /'

section "16. Blame and file history of the new path (cases 1 and 2), as Leaf runs them"
R="$(new_repo blame1)"; git -C "$R" mv a.txt b.txt
echo "  plain move:"; probe blame "$R" b.txt | head -3; probe log "$R" b.txt; probe log "$R" a.txt
R="$(new_repo blame2)"; git -C "$R" mv a.txt b.txt; sed -i '' 's/alpha line 2$/changed 2/' "$R/b.txt"; git -C "$R" add b.txt
echo "  move with an edit on line 2:"; probe blame "$R" b.txt | head -3
echo "  the same, blamed from the old path at HEAD with b.txt as the uncommitted version:"
probe blame-moved "$R" a.txt b.txt | head -3
