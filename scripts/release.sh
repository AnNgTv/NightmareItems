#!/data/data/com.termux/files/usr/bin/bash
set -e

git fetch --tags origin

LAST=$(git tag -l 'v*' --sort=-v:refname | head -n 1)

if [ -z "$LAST" ]; then
  VERSION="1.0.0"
else
  VERSION="${LAST#v}"
  IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
  PATCH=$((PATCH + 1))
  VERSION="$MAJOR.$MINOR.$PATCH"
fi

TAG="v$VERSION"

git tag "$TAG"
git push origin "$TAG"

gh release create "$TAG" \
  --title "NightmareItems $TAG" \
  --generate-notes

echo "Da tao Release: $TAG"
