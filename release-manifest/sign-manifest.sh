#!/bin/sh
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only

# Writes a release's update manifest, leaf-update.json, and signs it (docs/fork/auto-update.md, section 2).
#
#   sign-manifest.sh <version> <version code> <files folder> <private key> <key name> <output folder>
#
# The files folder holds the release's files, named as the build jobs name them. The output folder gets
# leaf-update.json and leaf-update.json.<key name>.sig. Nothing is written there unless the signature checks out
# against the key that Leaf trusts under that name (trusted-release-keys, or $LEAF_TRUSTED_RELEASE_KEYS for tests).
#
# The release workflow runs it in a job of its own with the signing key, so it uses nothing but the shell's tools and
# ssh-keygen: no build tool or downloaded code runs where the key is.

set -eu

here=$(cd "$(dirname "$0")" && pwd)
trusted=${LEAF_TRUSTED_RELEASE_KEYS:-$here/src/main/resources/dev/app/leaf/releasemanifest/trusted-release-keys}
namespace=leaf-release
repository=zzhelev/Leaf

fail() {
    echo "sign-manifest.sh: $*" >&2
    exit 1
}

[ $# -eq 6 ] ||
    fail "usage: sign-manifest.sh <version> <version code> <files folder> <private key> <key name> <output folder>"

version=$1
code=$2
files=$3
key=$4
name=$5
out=$6

# Three numbers, as ReleaseManifest.kt's isReleaseVersion: the version goes into file names and addresses
case $version in
    '' | *[!0-9.]* | .* | *. | *..*) fail "\"$version\" isn't a release version, such as 1.11.0" ;;
esac
[ "$(printf %s "$version" | tr -cd . | wc -c | tr -d ' ')" = 2 ] ||
    fail "\"$version\" isn't a release version, such as 1.11.0"

case $code in
    '' | *[!0-9]* | ??????????*) fail "\"$code\" isn't a version code" ;;
esac

# As ReleaseSignatures.kt's isKeyName: the name goes into a file name
case $name in
    '' | [!a-z0-9]* | *[!a-z0-9-]*) fail "\"$name\" isn't a key name" ;;
esac

[ -d "$files" ] || fail "There's no files folder $files"
[ -s "$key" ] || fail "There's no signing key in $key. In the release workflow, it's the LEAF_RELEASE_KEY secret of the release-signing environment."
[ -f "$trusted" ] || fail "There's no trusted keys file $trusted"

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d ' ' -f 1; }
else
    sha256() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
fi

# Each kind and its file, as ReleaseFileKind in ReleaseManifest.kt names them, sorted by kind
kinds='linux-aarch64-jar Leaf-VERSION-linux-arm_aarch64.jar
linux-amd64-deb Leaf-VERSION-linux-amd64.deb
linux-arm64-deb Leaf-VERSION-linux-arm64.deb
linux-x86_64-jar Leaf-VERSION-linux-x86_64.jar
macos-arm64 Leaf-VERSION-macos-arm64.dmg
windows-x64-portable Leaf-VERSION-windows-x64-portable.zip
windows-x64-setup Leaf-VERSION-windows-x64-setup.exe'

missing=
for file in $(printf '%s\n' "$kinds" | cut -d ' ' -f 2 | sed "s/VERSION/$version/"); do
    [ -f "$files/$file" ] || missing="$missing $file"
done
[ -z "$missing" ] || fail "The files folder lacks$missing"

work=$(mktemp -d "${TMPDIR:-/tmp}/leaf-sign-manifest.XXXXXX")
trap 'rm -rf "$work"' EXIT
manifest=$work/leaf-update.json

{
    printf '{\n'
    printf '    "format": 1,\n'
    printf '    "appVersion": "%s",\n' "$version"
    printf '    "appCode": %s,\n' "$code"
    printf '    "releasePage": "https://github.com/%s/releases/tag/leaf-%s",\n' "$repository" "$version"
    printf '    "files": {'
    separator=
    printf '%s\n' "$kinds" | while read -r kind pattern; do
        file=$(printf %s "$pattern" | sed "s/VERSION/$version/")
        size=$(wc -c < "$files/$file" | tr -d ' ')
        printf '%s\n        "%s": {\n' "$separator" "$kind"
        printf '            "name": "%s",\n' "$file"
        printf '            "size": %s,\n' "$size"
        printf '            "sha256": "%s"\n' "$(sha256 "$files/$file")"
        printf '        }'
        separator=,
    done
    printf '\n    }\n}\n'
} > "$manifest"

# A signature from the agent's keys, or anything but this key, mustn't be made by mistake
unset SSH_AUTH_SOCK
ssh-keygen -q -Y sign -n "$namespace" -f "$key" "$manifest" || fail "ssh-keygen couldn't sign with $key"

# Leaf's check, done by ssh-keygen: the key must be the one that Leaf trusts as $name
# Not -v namespace: gawk 5 refuses its builtin's name as a variable
awk -v signing_namespace="$namespace" '
    /^[[:space:]]*(#|$)/ { next }
    NF >= 3 { printf "%s namespaces=\"%s\" %s %s\n", $1, signing_namespace, $2, $3 }
' "$trusted" > "$work/allowed-signers"

grep -q "^$name " "$work/allowed-signers" || fail "Leaf trusts no key named $name ($trusted)"

ssh-keygen -Y verify -f "$work/allowed-signers" -I "$name" -n "$namespace" -s "$manifest.sig" \
    < "$manifest" > /dev/null 2>&1 ||
    fail "The key in $key isn't the one that Leaf trusts as $name ($trusted)"

mkdir -p "$out"
cp "$manifest" "$out/leaf-update.json"
cp "$manifest.sig" "$out/leaf-update.json.$name.sig"
echo "Signed leaf-update.json of $version ($code) as $name"
