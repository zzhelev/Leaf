#!/bin/sh
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only

# Attaches a signed update manifest to its release, through gh (docs/fork/auto-update.md, section 2).
#
#   attach-manifest.sh <tag> <folder>
#
# The folder holds leaf-update.json and its signatures, leaf-update.json.<key name>.sig, as sign-manifest.sh writes
# them. When the release has a manifest already, signed another way, it must be the same bytes, and only the
# signatures are attached: another manifest would replace the one that the signatures there were made of.
# The release workflow runs it after the sign job, and sign-release.sh after signing on a computer.

set -eu

repository=zzhelev/Leaf

fail() {
    echo "attach-manifest.sh: $*" >&2
    exit 1
}

[ $# -eq 2 ] || fail "usage: attach-manifest.sh <tag> <folder>"

tag=$1
signed=$2

[ -f "$signed/leaf-update.json" ] || fail "There's no leaf-update.json in $signed"
cd "$signed"

signatures=
for file in leaf-update.json.*.sig; do
    [ -f "$file" ] || continue
    name=${file#leaf-update.json.}
    name=${name%.sig}
    case $name in
        '' | [!a-z0-9]* | *[!a-z0-9-]*) fail "$file isn't named after a key" ;;
    esac
    signatures="$signatures $file"
done
[ -n "$signatures" ] || fail "There's no signature in $signed"

assets=$(gh release view "$tag" --repo "$repository" --json assets --jq '.assets[].name') ||
    fail "Couldn't find the release $tag on GitHub"

work=$(mktemp -d "${TMPDIR:-/tmp}/leaf-attach-manifest.XXXXXX")
trap 'rm -rf "$work"' EXIT

if printf '%s\n' "$assets" | grep -qx 'leaf-update.json'; then
    gh release download "$tag" --repo "$repository" --pattern leaf-update.json --dir "$work" --clobber ||
        fail "Couldn't download the release's leaf-update.json"
    cmp -s "$work/leaf-update.json" leaf-update.json ||
        fail "The release's leaf-update.json isn't the one signed here, so nothing was attached. Compare the two before attaching anything."
    uploads=$signatures
else
    uploads="leaf-update.json$signatures"
fi

# The names have no spaces: leaf-update.json, and key names of a-z, 0-9 and -
# shellcheck disable=SC2086
gh release upload "$tag" $uploads --repo "$repository" --clobber || fail "Couldn't attach the files to the release $tag"

for file in $uploads; do
    echo "Attached $file to $tag"
done
