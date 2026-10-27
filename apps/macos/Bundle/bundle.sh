#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds apps/macos/build/Sukrara.app with Contents/PlugIns/SukraraWidget.appex from `swift build -c release`.
#   Bundle/bundle.sh             signed with SIGN_ID, else the first "Apple Development" identity of team QWUV9H4A3G
#                                (macOS only loads widgets whose signature carries a Team ID; a team-prefixed App Group
#                                needs no provisioning profile). Hardened runtime, inside-out (appex, then app).
#   Bundle/bundle.sh --unsigned  ad hoc signature (CI, no identity): verifies structurally, widgets won't load.
#                                AD_HOC=1 does the same.
#   Bundle/bundle.sh --dev       CFBundleVersion <versionCode>.<UTC yyMMddHHmm>: chronod keeps a widget's gallery
#                                previews while CFBundleVersion stays the same.
#   NOTARY_PROFILE=<profile>     notarize + staple (needs a Developer ID identity; not used for 0.90.x).
# Version: <repo>/VERSION (x.y.z); CFBundleVersion = versionCode = x*1000000 + y*1000 + z (0.90.0 → 90000).
# Never copy the result into ~/Applications by hand: tools/install.sh mac does that.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=$(dirname "$HERE")
REPO=$(cd "$PKG/../.." && pwd)
TEAM=QWUV9H4A3G
GROUP=$TEAM.app.mauri.sukrara
die() { echo "bundle.sh: $*" >&2; exit 1; }

UNSIGNED=0; DEV=0
for arg in "$@"; do
  case "$arg" in
    --unsigned) UNSIGNED=1 ;;
    --dev) DEV=1 ;;
    -h|--help) sed -n '5,17p' "$0"; exit 0 ;;
    *) die "unknown argument '$arg' (--unsigned, --dev)" ;;
  esac
done
[ "${AD_HOC:-}" = 1 ] && UNSIGNED=1

# Version
VERSION=$(tr -d ' \n' < "$REPO/VERSION")
echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "VERSION '$VERSION' is not x.y.z"
CODE=$(echo "$VERSION" | awk -F. '{ print $1 * 1000000 + $2 * 1000 + $3 }')
BUILD=$CODE
[ "$DEV" = 1 ] && BUILD="$CODE.$(date -u +%y%m%d%H%M)"

# Identity (before building, so a missing one fails fast)
SIGN=-; TS=--timestamp=none
if [ "$UNSIGNED" = 0 ]; then
  IDS=$(security find-identity -v -p codesigning)
  if [ -z "${SIGN_ID:-}" ]; then
    # The name's "(…)" is the personal id, not the team: the team is checked on the certificate below.
    SIGN_ID=$(echo "$IDS" | awk -F'"' '/Apple Development/ {print $2; exit}')
  fi
  [ -n "$SIGN_ID" ] || die "no \"Apple Development\" identity in the keychain (Xcode → Settings → Accounts → Manage
  Certificates), or pass --unsigned"
  echo "$IDS" | grep -qF "$SIGN_ID" || die "SIGN_ID '$SIGN_ID' is not a valid code-signing identity"
  CERT_TEAM=$(security find-certificate -c "$SIGN_ID" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F' = ' '/organizationalUnitName/{print $2; exit}')
  [ "$CERT_TEAM" = "$TEAM" ] || die "identity '$SIGN_ID' belongs to team '${CERT_TEAM:-none}', not $TEAM: the App Group
  $GROUP would not be granted"
  SIGN=$SIGN_ID
  case "$SIGN_ID" in "Developer ID Application:"*) TS=--timestamp ;; esac
fi

# Resources: V1 locations, PoC fallbacks until chunk 01 moves them
first_existing() { for p in "$@"; do [ -e "$p" ] && { echo "$p"; return; }; done; }
ICON=$(first_existing "$REPO/assets/brand/sukrara/macos/AppIcon.icns" "$REPO/poc/spikes/brand/assets/sukrara/macos/AppIcon.icns")
SOUNDS=$(first_existing "$REPO/contracts/alerts/sounds/mac" "$REPO/poc/spikes/alerts/sounds/out")
missing() {
  if [ "$UNSIGNED" = 1 ]; then echo "bundle.sh: warning: $* (unsigned build continues)" >&2
  else die "$* (a signed build ships alert sounds and the icon)"; fi
}
[ -n "$ICON" ] || missing "no AppIcon.icns"
[ -n "$SOUNDS" ] && ls "$SOUNDS"/*.caf >/dev/null 2>&1 || missing "no alert sounds (*.caf)"

cd "$PKG"
swift build -c release --product Sukrara
swift build -c release --product SukraraWidget
BIN=$(swift build -c release --show-bin-path)

OUT=$PKG/build
APP=$OUT/Sukrara.app
APPEX=$APP/Contents/PlugIns/SukraraWidget.appex
rm -rf "$APP" "$OUT/entitlements"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/Sounds" "$APPEX/Contents/MacOS" "$OUT/entitlements"
cp "$BIN/Sukrara" "$APP/Contents/MacOS/Sukrara"
cp "$BIN/SukraraWidget" "$APPEX/Contents/MacOS/SukraraWidget"
[ -n "$ICON" ] && cp "$ICON" "$APP/Contents/Resources/AppIcon.icns"
[ -n "$SOUNDS" ] && cp "$SOUNDS"/*.caf "$APP/Contents/Resources/Sounds/" 2>/dev/null || true

fill() { sed -e "s/@VERSION@/$VERSION/g" -e "s/@BUILD@/$BUILD/g" -e "s/@GROUP@/$GROUP/g" "$1" > "$2"; plutil -lint -s "$2"; }
fill "$HERE/Info.plist.in" "$APP/Contents/Info.plist"
fill "$HERE/Widget-Info.plist.in" "$APPEX/Contents/Info.plist"
fill "$HERE/app.entitlements.in" "$OUT/entitlements/app.plist"
fill "$HERE/widget.entitlements.in" "$OUT/entitlements/widget.plist"

# No extended attributes or .DS_Store inside the bundle: they break `codesign --strict`.
xattr -cr "$APP"
find "$APP" -name .DS_Store -delete
# Inside-out: the extension first, then the app (which seals the extension's signature into its own).
codesign --force --sign "$SIGN" --options runtime $TS --entitlements "$OUT/entitlements/widget.plist" "$APPEX"
codesign --force --sign "$SIGN" --options runtime $TS --entitlements "$OUT/entitlements/app.plist" "$APP"
codesign --verify --deep --strict "$APP"
for C in "$APP" "$APPEX"; do
  if codesign -d --entitlements - "$C" 2>/dev/null | grep -q get-task-allow; then die "$C has get-task-allow"; fi
done

if [ -n "${NOTARY_PROFILE:-}" ]; then
  [ "$TS" = --timestamp ] || die "NOTARY_PROFILE needs a Developer ID Application identity (SIGN_ID)"
  ZIP=$OUT/Sukrara-notarize.zip; rm -f "$ZIP"
  ditto -c -k --keepParent "$APP" "$ZIP"
  xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait | tee "$OUT/notary.log"
  rm -f "$ZIP"
  grep -q 'status: Accepted' "$OUT/notary.log" || die "notarization failed (see $OUT/notary.log)"
  xcrun stapler staple "$APP"
fi

if [ "$SIGN" = - ]; then SIGNED_BY="ad hoc, widgets won't load"; else SIGNED_BY="$SIGN"; fi
echo "built $APP $VERSION ($BUILD), signed: $SIGNED_BY"
