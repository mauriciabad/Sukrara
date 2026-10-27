#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Packages build/Sukrara.app (run Bundle/bundle.sh first) as build/Sukrara-<version>.dmg (a window with the app, an
# arrow and an Applications shortcut; needs dmgbuild: `uv tool install dmgbuild`, else a plain image with the same
# two items) and build/Sukrara-mac-<version>.zip (ditto, keeps the signature). The dmg is signed with the app's
# identity and checked by mounting it and verifying the app inside.
# NOTARY_PROFILE=<profile>: notarize + staple the dmg (needs a Developer ID build; not used for 0.90.x). Without it,
# Gatekeeper asks on first launch (System Settings → Privacy & Security → Open Anyway).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(dirname "$HERE")/build
APP=$OUT/Sukrara.app
die() { echo "dmg.sh: $*" >&2; exit 1; }
[ -d "$APP" ] || die "no $APP: run Bundle/bundle.sh first"
codesign --verify --deep --strict "$APP" || die "$APP has an invalid signature: rebuild with Bundle/bundle.sh"
ID=$(codesign -dvv "$APP" 2>&1 | sed -n 's/^Authority=//p' | head -1)
case "$ID" in "Developer ID Application:"*) TS=--timestamp ;; *) TS=--timestamp=none ;; esac
if [ -n "${NOTARY_PROFILE:-}" ]; then
  [ "$TS" = --timestamp ] || die "NOTARY_PROFILE needs an app signed with a Developer ID Application identity"
  xcrun stapler validate -q "$APP" || die "$APP is not notarized: run NOTARY_PROFILE=$NOTARY_PROFILE Bundle/bundle.sh"
fi
VER=$(plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")
DMG=$OUT/Sukrara-$VER.dmg
W=$OUT/dmg
rm -rf "$W" "$DMG"
mkdir -p "$W"
if command -v dmgbuild >/dev/null; then
  swift "$HERE/dmg/background.swift" "$W"
  tiffutil -cathidpicheck "$W/background.png" "$W/background@2x.png" -out "$W/background.tiff" >/dev/null
  dmgbuild -s "$HERE/dmg/settings.py" -D app="$APP" -D background="$W/background.tiff" Sukrara "$DMG"
else
  echo "dmg.sh: dmgbuild not found: plain image without the window layout (uv tool install dmgbuild)" >&2
  mkdir -p "$W/root"
  cp -R "$APP" "$W/root/"
  ln -s /Applications "$W/root/Applications"
  hdiutil create -volname Sukrara -srcfolder "$W/root" -ov -format UDZO "$DMG" >/dev/null
fi
[ -n "$ID" ] && codesign --force --sign "$ID" $TS "$DMG"
# The app as the user gets it: mount read-only and verify the copy inside.
MNT=$(mktemp -d "$OUT/mnt.XXXXXX")
hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$MNT" "$DMG" >/dev/null
OK=1
codesign --verify --deep --strict "$MNT/Sukrara.app" || OK=0
hdiutil detach "$MNT" >/dev/null
rmdir "$MNT"
[ "$OK" = 1 ] || die "the app inside $DMG fails codesign --verify --deep --strict"
if [ -n "${NOTARY_PROFILE:-}" ]; then
  xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait | tee "$OUT/notary-dmg.log"
  grep -q 'status: Accepted' "$OUT/notary-dmg.log" || die "notarization of $DMG failed (see $OUT/notary-dmg.log)"
  xcrun stapler staple "$DMG"
fi
ZIP=$OUT/Sukrara-mac-$VER.zip
rm -f "$ZIP"
ditto -c -k --keepParent "$APP" "$ZIP"
echo "built $DMG${ID:+ (signed: $ID)} and $ZIP"
