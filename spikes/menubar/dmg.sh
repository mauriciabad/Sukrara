#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Package build/Sukrara.app (run ./bundle.sh first) as the user-facing installer:
# build/Sukrara-<version>.dmg, a window with the app, an arrow and an Applications shortcut to drag it onto.
# The layout needs dmgbuild (`uv tool install dmgbuild`); without it you get a plain image with the same two items.
# The dmg is signed with the app's identity when it has one; it is checked by mounting it and verifying the app inside.
# Also writes build/Sukrara-mac-<version>.zip (ditto, keeps the signature) for the release.
# Release (Developer ID): NOTARY_PROFILE=<notarytool keychain profile> ./dmg.sh after the same bundle.sh run (see the
# top of bundle.sh for the one-time setup): the dmg is signed with a timestamp, notarized, stapled and assessed.
# Without Developer ID the dmg still opens, but Gatekeeper blocks the first launch ("Apple could not verify…"): the
# user clicks Open Anyway in System Settings → Privacy & Security (README, Try it).
set -e
cd "$(dirname "$0")"
die() { echo "dmg.sh: $*" >&2; exit 1; }
APP=build/Sukrara.app
[ -d "$APP" ] || die "no $APP: run ./bundle.sh first"
codesign --verify --deep --strict "$APP" || die "$APP has an invalid signature: rebuild with ./bundle.sh"
# Same identity as the app (bundle.sh's choice: TeamIdentifier set → that certificate, else none).
ID=$(codesign -dvv "$APP" 2>&1 | sed -n 's/^Authority=//p' | head -1)
case "$ID" in "Developer ID Application:"*) DEVID=1 ;; *) DEVID=0 ;; esac
if [ -n "${NOTARY_PROFILE:-}" ]; then
  [ "$DEVID" = 1 ] || die "NOTARY_PROFILE is set but $APP is signed by '${ID:-ad hoc}', not a Developer ID Application
  identity: run SIGN_ID=\"Developer ID Application: …\" NOTARY_PROFILE=$NOTARY_PROFILE ./bundle.sh first"
  xcrun stapler validate -q "$APP" || die "$APP is not notarized/stapled: run NOTARY_PROFILE=$NOTARY_PROFILE ./bundle.sh first"
fi
VER=$(plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")
OUT=build/Sukrara-$VER.dmg
W=build/dmg; rm -rf $W "$OUT"; mkdir -p $W
if command -v dmgbuild >/dev/null; then
  swift dmg/background.swift $W
  tiffutil -cathidpicheck $W/background.png $W/background@2x.png -out $W/background.tiff >/dev/null
  dmgbuild -s dmg/settings.py -D app="$APP" -D background=$W/background.tiff Sukrara "$OUT"
else
  echo "dmgbuild not found: plain image without the window layout (uv tool install dmgbuild)" >&2
  mkdir -p $W/root && cp -R "$APP" $W/root/ && ln -s /Applications $W/root/Applications
  hdiutil create -volname Sukrara -srcfolder $W/root -ov -format UDZO "$OUT" >/dev/null
fi
if [ -n "$ID" ]; then
  if [ "$DEVID" = 1 ]; then TS=--timestamp; else TS=--timestamp=none; fi
  codesign --force --sign "$ID" $TS "$OUT"
fi
# The app as the user gets it: mount read-only and verify the copy inside (dmgbuild must not have added xattrs).
MNT=$(mktemp -d build/mnt.XXXXXX)
hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$MNT" "$OUT" >/dev/null
OK=1; codesign --verify --deep --strict "$MNT/Sukrara.app" || OK=0
hdiutil detach "$MNT" >/dev/null; rmdir "$MNT"
[ "$OK" = 1 ] || die "the app inside $OUT fails codesign --verify --deep --strict"
if [ -n "${NOTARY_PROFILE:-}" ]; then
  RES=$(xcrun notarytool submit "$OUT" --keychain-profile "$NOTARY_PROFILE" --wait) || true
  echo "$RES"
  if ! echo "$RES" | grep -q 'status: Accepted'; then
    SUB=$(echo "$RES" | awk '/^ *id:/{print $2; exit}')
    [ -n "$SUB" ] && xcrun notarytool log "$SUB" --keychain-profile "$NOTARY_PROFILE" >&2
    die "notarization of $OUT failed (log above)"
  fi
  xcrun stapler staple "$OUT"
  xcrun stapler validate "$OUT"
  spctl -a -t open --context context:primary-signature -vv "$OUT"
fi
ZIP=build/Sukrara-mac-$VER.zip; rm -f "$ZIP"
ditto -c -k --keepParent "$APP" "$ZIP"
echo "built $OUT${ID:+ (signed: $ID)}$( [ -n "${NOTARY_PROFILE:-}" ] && echo ', notarized + stapled') and $ZIP"
