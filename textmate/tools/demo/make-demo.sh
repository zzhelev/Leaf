#!/bin/sh
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: MIT

# Makes a repository to look at Leaf's syntax colours in by hand (docs/fork/syntax-highlighting.md, step 7): a file in
# each language that Leaf ships a grammar for, from the conformance samples, in commits whose diffs add, take out and
# change lines in every file, and a merge stopped at conflicts in four of them, for the conflict resolver.
#
# Usage: textmate/tools/demo/make-demo.sh [folder]      (the folder is /tmp/leaf-syntax-demo unless given)
# Then, from the repository's root: ./gradlew :app:run --args="<folder>"
#
# It makes the folder again on each run, and touches nothing but a folder that it made.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
SAMPLES="$HERE/../../src/test/resources/conformance/samples"
DEMO=${1:-/tmp/leaf-syntax-demo}

if [ -e "$DEMO" ]; then
    if [ -f "$DEMO/.git/leaf-syntax-demo" ]; then
        rm -rf "$DEMO"
    else
        echo "$DEMO is there and this script didn't make it: leaving it alone" >&2
        exit 1
    fi
fi

mkdir -p "$DEMO"
cd "$DEMO"
git init -q -b main
touch .git/leaf-syntax-demo
git config user.name "Leaf Demo"
git config user.email "demo@example.com"
git config commit.gpgSign false

commit() {
    git add -A
    git commit -q -m "$1"
}

# Changes the first string in quotes in a file, before its closing quote, or takes its second line out when it has none
change() {
    if grep -q '"[^"]\{1,\}"' "$1"; then
        awk -v added="$2" '
            !done && match($0, /"[^"]+"/) {
                $0 = substr($0, 1, RSTART + RLENGTH - 2) added substr($0, RSTART + RLENGTH - 1)
                done = 1
            }
            { print }
        ' "$1" > "$1.tmp"
    else
        awk 'NR != 2' "$1" > "$1.tmp"
    fi
    mv "$1.tmp" "$1"
}

# Each sample in a folder named after its language, so that names such as Dockerfile and .editorconfig say what they are
for dir in "$SAMPLES"/*/; do
    language=$(basename "$dir")
    mkdir -p "$language"

    for file in "$dir"* "$dir".[!.]*; do
        [ -f "$file" ] || continue
        # Without its middle third, which the next commit adds
        lines=$(wc -l < "$file")
        awk -v lines="$lines" 'NR <= lines / 3 || NR > 2 * lines / 3' "$file" > "$language/$(basename "$file")"
    done
done
commit "Add a file in each language, without its middle lines"

for dir in "$SAMPLES"/*/; do
    cp -R "$dir". "$(basename "$dir")/"
done
commit "Add the middle lines"

for file in */* */.[!.]*; do
    [ -f "$file" ] || continue
    change "$file" " v2"
done
commit "Change a line in each file"

# The same strings changed on two branches: a merge that stops at conflicts
CONFLICTS="kotlin/Branches.kt json/package.json python/blame.py typescript/compare.ts"

git checkout -q -b feature
for file in $CONFLICTS; do
    change "$file" " from feature"
done
commit "Change the strings on feature"

git checkout -q main
for file in $CONFLICTS; do
    change "$file" " from main"
done
commit "Change the strings on main"

git merge -q -m "Merge feature" feature > /dev/null 2>&1 || true

echo "Made $DEMO: $(git rev-list --count HEAD) commits on main, conflicts in:"
git diff --name-only --diff-filter=U | sed 's/^/  /'
