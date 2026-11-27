#!/bin/sh
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only

# Signs a release's update manifest on this computer, with this computer's key: the other way to sign, beside the
# release workflow's sign job (docs/fork/auto-update.md, section 2).
#
#   sign-release.sh <version> [--key <private key>] [--keep]
#
# It checks that the tag leaf-<version> is the same commit here and on GitHub and that APP_VERSION there is the
# version, downloads the release's files (about 1 GB), checks GitHub's attestation of each (Leaf's release workflow
# built it from that commit, on GitHub's own runners), then writes and signs the manifest with sign-manifest.sh, which
# asks for the key's passphrase, and attaches it with attach-manifest.sh. Nothing is attached unless every check
# passes.
#
# The key is ~/.ssh/leaf-release unless --key names another, and its name is the one that trusted-release-keys gives
# its public half (<key>.pub). It needs git, ssh-keygen, and gh logged in as an account that can edit the release.
# --keep keeps the downloaded files.

set -eu

here=$(cd "$(dirname "$0")" && pwd)
repository_dir=${LEAF_REPOSITORY_DIR:-$here/..}
trusted=${LEAF_TRUSTED_RELEASE_KEYS:-$here/src/main/resources/dev/app/leaf/releasemanifest/trusted-release-keys}
repository=zzhelev/Leaf
workflow=.github/workflows/release.yml
constants=app/src/main/kotlin/dev/app/leaf/AppConstants.kt
usage="usage: sign-release.sh <version> [--key <private key>] [--keep]"

fail() {
    echo "sign-release.sh: $*" >&2
    exit 1
}

[ $# -ge 1 ] || fail "$usage"
version=$1
shift
key=$HOME/.ssh/leaf-release
keep=false

while [ $# -gt 0 ]; do
    case $1 in
        --key)
            [ $# -ge 2 ] || fail "$usage"
            key=$2
            shift 2
            ;;
        --keep)
            keep=true
            shift
            ;;
        *) fail "$usage" ;;
    esac
done

case $version in
    '' | *[!0-9.]* | .* | *. | *..*) fail "\"$version\" isn't a release version, such as 1.11.0" ;;
esac
[ "$(printf %s "$version" | tr -cd . | wc -c | tr -d ' ')" = 2 ] ||
    fail "\"$version\" isn't a release version, such as 1.11.0"

tag=leaf-$version

[ -f "$key" ] || fail "There's no key $key. Make one with: ssh-keygen -t ed25519 -f $key -C \"leaf-release <this computer>\""
[ -f "$key.pub" ] || fail "There's no $key.pub next to the key"
[ -f "$trusted" ] || fail "There's no trusted keys file $trusted"

# The key's name: the one that a trusted line with this public key gives it
public=$(awk 'NF >= 2 { print $1, $2; exit }' "$key.pub")
name=$(awk -v public="$public" '!/^[[:space:]]*#/ && NF >= 3 && $2 " " $3 == public { print $1; exit }' "$trusted")
[ -n "$name" ] || fail "This computer's key isn't one that Leaf trusts. A release has to add it to $trusted first, as a line with a name of its own:
<name> $(cat "$key.pub")"

commit=$(git -C "$repository_dir" rev-parse --verify --quiet "refs/tags/$tag^{commit}") ||
    fail "There's no tag $tag here. Fetch the tags (git fetch origin --tags), or check the version."
github_commit=$(gh api "repos/$repository/commits/refs/tags/$tag" --jq .sha) ||
    fail "GitHub has no tag $tag, or gh couldn't ask it"
[ "$commit" = "$github_commit" ] || fail "The tag $tag is $commit here, and $github_commit on GitHub."

app_constants=$(git -C "$repository_dir" show "$commit:$constants") || fail "$tag has no $constants"
app_version=$(printf '%s\n' "$app_constants" | sed -n 's/^ *const val APP_VERSION = "\(.*\)"$/\1/p')
code=$(printf '%s\n' "$app_constants" | sed -n 's/^ *const val APP_VERSION_CODE = \([0-9][0-9]*\)$/\1/p')
[ "$app_version" = "$version" ] || fail "APP_VERSION at $tag is \"$app_version\", not $version."
[ -n "$code" ] || fail "$constants at $tag has no APP_VERSION_CODE."

gh release view "$tag" --repo "$repository" --json assets --jq '.assets[].name' > /dev/null ||
    fail "There's no release $tag on GitHub."

echo "$tag is $commit, version code $code. Signing as $name."

work=$(mktemp -d "${TMPDIR:-/tmp}/leaf-sign-release.XXXXXX")
if [ "$keep" = true ]; then
    trap 'echo "The downloaded files are in $work"' EXIT
else
    trap 'rm -rf "$work"' EXIT
fi
mkdir "$work/files"

echo "Downloading the release's files"
gh release download "$tag" --repo "$repository" --pattern "Leaf-$version-*" --dir "$work/files" --clobber ||
    fail "Couldn't download the release's files"
rm -f "$work/files"/*.sha256

# Every file, so that one that the workflow didn't build stops it too
for file in "$work/files"/*; do
    [ -f "$file" ] || continue
    echo "Checking GitHub's attestation of $(basename "$file")"
    gh attestation verify "$file" --repo "$repository" --signer-workflow "$repository/$workflow" \
        --source-ref "refs/tags/$tag" --source-digest "$commit" --deny-self-hosted-runners \
        > "$work/attestation.log" 2>&1 || {
        cat "$work/attestation.log" >&2
        fail "GitHub's attestation of $(basename "$file") doesn't check out, so nothing was attached."
    }
done

LEAF_TRUSTED_RELEASE_KEYS=$trusted "$here/sign-manifest.sh" "$version" "$code" "$work/files" "$key" "$name" "$work/signed"
"$here/attach-manifest.sh" "$tag" "$work/signed"
