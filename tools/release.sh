#!/usr/bin/env bash
# One-command release (428). Usage: tools/release.sh 1.7.0 notes.txt
#   notes.txt = the Chinese release notes (become the annotated tag message and the in-app "本次更新").
# Steps: clean tree on main -> JS tests -> tag vX.Y.Z -> push -> wait for the Release asset
# YiWei-X.Y.Z.apk -> bump baseVersion to the next minor -> commit + push.
set -euo pipefail
VER="${1:?version, e.g. 1.7.0}"
NOTES="${2:?file with the Chinese release notes}"
cd "$(git rev-parse --show-toplevel)"
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "bad version $VER"; exit 1; }
[ -s "$NOTES" ] || { echo "notes file is empty"; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "not on main"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "working tree not clean"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main is not in sync with origin/main"; exit 1; }
git rev-parse -q --verify "refs/tags/v$VER" >/dev/null && { echo "tag v$VER exists"; exit 1; }
grep -q "def baseVersion = \"$VER\"" app/build.gradle || echo "note: baseVersion in app/build.gradle is not $VER"

node tools/build-js.js --check
(cd tests/js && npm ci --no-audit --no-fund >/dev/null && npm test)

git tag -a "v$VER" -F "$NOTES"
git push origin "v$VER"

URL="https://github.com/yywgov/youtube/releases/expanded_assets/v$VER"
echo "waiting for YiWei-$VER.apk on $URL ..."
for i in $(seq 1 60); do
  if curl -fsSL "$URL" 2>/dev/null | grep -q "YiWei-$VER.apk"; then echo "release asset is up"; break; fi
  [ "$i" = 60 ] && { echo "timed out waiting for the release asset (check the Actions run)"; exit 1; }
  sleep 30
done

IFS=. read -r MA MI PA <<<"$VER"
NEXT="$MA.$((MI + 1)).0"
sed -i "s/^def baseVersion = \".*\"/def baseVersion = \"$NEXT\"/" app/build.gradle
git commit -qam "Bump baseVersion to $NEXT"
git push -q origin main
echo "released v$VER, baseVersion now $NEXT"
