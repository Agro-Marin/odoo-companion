#!/usr/bin/env bash
# Publish a signed release on GitHub, the one the app's updater reads.
#
# Usage: tools/release.sh [--notes "what changed"] [--draft]
#
# Builds the signed release APK, checks its wire format, and creates the GitHub
# release v<versionName> with two assets: the APK and update.json, the manifest
# the handset reads from releases/latest/download/ (see update/UpdateFeed.kt).
# Handsets offer it as soon as the release is published and is not a draft or
# a pre-release, so --draft is how a build is checked before the fleet sees it.
#
# It refuses to publish what the fleet could not install or would not offer:
#   - an unsigned build (no keystore.properties): Android installs nothing else;
#   - a versionCode the latest release already carries: no handset would see it;
#   - a tag that already exists;
#   - a build of uncommitted or unpushed code: the tag has to name the commit
#     the APK was built from, and GitHub can only tag a commit it has.
# Every published build has to be signed with the same key as the one on the
# handsets, or Android refuses it as an update (README, "Signing a release").
set -euo pipefail

cd "$(dirname "$0")/.."

REPO=Agro-Marin/odoo-companion
notes=""
draft=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --notes) notes="$2"; shift 2 ;;
        --draft) draft=(--draft); shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ ! -f keystore.properties ]]; then
    echo "keystore.properties is missing: a release must be signed (README, Signing a release)" >&2
    exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
    echo "the working tree has uncommitted changes: release a commit" >&2
    exit 1
fi
commit=$(git rev-parse HEAD)
if [[ -z "$(git branch -r --contains "$commit")" ]]; then
    echo "commit $commit is not pushed: push it first" >&2
    exit 1
fi

code=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
if [[ -z "$code" || -z "$name" ]]; then
    echo "could not read versionCode / versionName from app/build.gradle.kts" >&2
    exit 1
fi
tag="v$name"

if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
    echo "release $tag already exists: raise versionCode and versionName first" >&2
    exit 1
fi
published=$(gh release download -R "$REPO" -p update.json -O - 2>/dev/null \
    | sed -n 's/.*"version_code": *\([0-9]*\).*/\1/p' || true)
if [[ -n "$published" && "$code" -le "$published" ]]; then
    echo "versionCode $code is not above the published $published: no handset would offer it" >&2
    exit 1
fi

./gradlew assembleRelease
tools/check-release-wire.sh

built=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$built" ]]; then
    echo "no signed APK at $built" >&2
    exit 1
fi

out=build/release
mkdir -p "$out"
apk="odoo-companion-$code.apk"
cp "$built" "$out/$apk"
sha=$(sha256sum "$out/$apk" | cut -d' ' -f1)
size=$(stat -c %s "$out/$apk")
cat > "$out/update.json" <<EOF
{"version_code":$code,"version_name":"$name","apk":"$apk","sha256":"$sha","size":$size}
EOF

gh release create "$tag" -R "$REPO" "${draft[@]}" --target "$commit" \
    --title "Odoo Companion $name" \
    --notes "${notes:-Odoo Companion $name (versionCode $code)}" \
    "$out/$apk" "$out/update.json"

echo "published $tag: $apk ($size bytes, sha256 $sha)"
