#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Build a release Sukrara.app ("Sukrara for Mac", formerly GlucoseBar; menubar-only). Ad hoc signed, or with an
# Apple-issued identity + desktop widgets when the keychain has one (see the widget section below).
# The Swift package/target is still called GlucoseBar; the bundle, executable and id are Sukrara's.
# Configure:  defaults write app.mauri.sukrara.poc url http://192.168.10.127:17580
#             defaults write app.mauri.sukrara.poc unit mmol      # optional
#             defaults write app.mauri.sukrara.poc secret '...'   # if Juggluco has an API secret
#             defaults write app.mauri.sukrara.poc nostr 'cgmnostr:1?...'   # legacy v1 Nostr link
# Protocol v2 (default): menubar → "Follow someone…" (paste https://sukrara.mauri.app/join#…),
# or open a sukrara:join#… link (URL scheme registered below; the web page's "Open in Sukrara" button uses it).
# First launch copies GlucoseBar's defaults + follower Keychain item (Migration.swift).
#
# Signing modes (all sign every nested binary with the same identity, hardened runtime, after `xattr -cr`):
#   default         first "Apple Development" identity in the keychain (else Developer ID, else ad hoc): widgets
#                   load on this Mac, but Gatekeeper on other Macs says "Apple could not verify…" (Open Anyway).
#   AD_HOC=1        ad hoc, no widgets.
#   SIGN_ID=…       pick the identity (sha1 or full name).
#   Release (Developer ID, notarized, stapled), needs the paid Apple Developer Program:
#     SIGN_ID="Developer ID Application: Name (TEAMID)" NOTARY_PROFILE=sukrara ./bundle.sh && NOTARY_PROFILE=sukrara ./dmg.sh
#   With NOTARY_PROFILE and no SIGN_ID the first "Developer ID Application" identity is used. bundle.sh notarizes a zip
#   of the app and staples the app (so the release zip and the copy dragged out of the dmg carry their ticket, also
#   offline); dmg.sh then notarizes and staples the dmg (Apple: staple the outermost container you ship).
#   One-time setup:
#     1. Developer ID Application certificate: Xcode → Settings → Accounts → (team) → Manage Certificates → + →
#        Developer ID Application (Account Holder role), or developer.apple.com → Certificates → +.
#     2. App-specific password at account.apple.com → Sign-In and Security → App-Specific Passwords, then
#        xcrun notarytool store-credentials sukrara --apple-id <apple id> --team-id <TEAMID> --password <app-specific>
#        (stored in the login keychain; never put the password in a file).
#   No provisioning profile is needed: the only restricted-looking entitlement is the App Group, and a macOS group
#   named "<TEAMID>.<name>" is authorised by the Team ID in the signature (developer.apple.com/documentation/xcode/
#   configuring-app-groups). No get-task-allow is ever added (checked below for Developer ID builds).
set -e
cd "$(dirname "$0")"
die() { echo "bundle.sh: $*" >&2; exit 1; }
IDS=$(security find-identity -v -p codesigning)
if [ -n "${NOTARY_PROFILE:-}" ] && [ -z "${SIGN_ID:-}" ]; then
  SIGN_ID=$(echo "$IDS" | awk -F'"' '/Developer ID Application/{print $2; exit}')
  [ -n "$SIGN_ID" ] || die "NOTARY_PROFILE is set but the keychain has no \"Developer ID Application\" identity
  (security find-identity -v -p codesigning). Create one: Xcode → Settings → Accounts → Manage Certificates → + →
  Developer ID Application (needs the paid Apple Developer Program)."
fi
if [ -z "${SIGN_ID:-}" ] && [ "${AD_HOC:-}" != 1 ]; then
  SIGN_ID=$(echo "$IDS" | awk -F'"' '/Apple Development/{print $2; exit}')
  [ -n "$SIGN_ID" ] || SIGN_ID=$(echo "$IDS" | awk -F'"' '/Developer ID Application/{print $2; exit}')
fi
DEVID=0
if [ -n "${SIGN_ID:-}" ]; then
  LINE=$(echo "$IDS" | grep -F "$SIGN_ID" | head -1)
  [ -n "$LINE" ] || die "SIGN_ID '$SIGN_ID' is not a valid code-signing identity in the keychain:
$IDS"
  case "$LINE" in *'"Developer ID Application:'*) DEVID=1 ;; esac
fi
if [ -n "${NOTARY_PROFILE:-}" ]; then
  [ "$DEVID" = 1 ] || die "NOTARY_PROFILE needs a Developer ID Application identity; SIGN_ID is '${SIGN_ID:-ad hoc}'"
  xcrun notarytool history --keychain-profile "$NOTARY_PROFILE" >/dev/null 2>&1 || die "notarytool keychain profile
  '$NOTARY_PROFILE' is missing or its credentials don't work. Create it once with:
  xcrun notarytool store-credentials $NOTARY_PROFILE --apple-id <apple id> --team-id <TEAMID> --password <app-specific password>"
elif [ "$DEVID" = 1 ]; then
  echo "bundle.sh: Developer ID signing without NOTARY_PROFILE: the app will not be notarized" >&2
fi
swift build -c release
APP=build/Sukrara.app
ICON=../brand/assets/sukrara/macos/AppIcon.icns
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/GlucoseBar "$APP/Contents/MacOS/Sukrara"
cp "$ICON" "$APP/Contents/Resources/AppIcon.icns"
# Alert sounds (spikes/alerts/sounds/out, IMA4 CAF, 4 packs × 8 kinds × 3 variants). The app copies the one a
# notification rings to ~/Library/Sounds first (NotificationSounds.swift): Notification Center didn't find them here.
cp ../alerts/sounds/out/*.caf "$APP/Contents/Resources/"
# A build number that changes with every build: macOS (chronod) keeps a widget extension's gallery previews and
# placeholders as long as its CFBundleVersion stays the same, so a constant "1" kept showing old previews.
BUILD=$(date +%Y%m%d%H%M%S)
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>app.mauri.sukrara.poc</string>
  <key>CFBundleName</key><string>Sukrara</string>
  <key>CFBundleDisplayName</key><string>Sukrara</string>
  <key>CFBundleExecutable</key><string>Sukrara</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.11.41</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>CFBundleURLTypes</key><array><dict>
    <key>CFBundleURLName</key><string>Sukrara invite</string>
    <key>CFBundleURLSchemes</key><array><string>sukrara</string></array>
  </dict></array>
  <key>NSBluetoothAlwaysUsageDescription</key><string>Read a FreeStyle Libre 3 sensor directly.</string>
  <key>NSLocalNetworkUsageDescription</key><string>Read glucose from Juggluco on your phone.</string>
</dict></plist>
EOF
plutil -insert CFBundleVersion -string "$BUILD" "$APP/Contents/Info.plist"

# Desktop / Notification Center widgets (spikes/widgets-macos, docs/13). macOS only loads a widget signed with a
# Team ID, so they are built only when an Apple-issued identity is in the keychain (Xcode → Settings → Accounts →
# Manage Certificates → Apple Development; a free personal team works on this Mac, a Developer ID for others).
# Without one (or with AD_HOC=1) the app is ad hoc signed with no widget, as before.
#   WIDGETS=0  sign with the identity but leave the widget out
TEAM_ID=""
if [ -n "${SIGN_ID:-}" ]; then
  TEAM_ID=$(security find-certificate -c "$SIGN_ID" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F' = ' '/organizationalUnitName/{print $2; exit}')
fi
# No extended attributes, resource forks or Finder info inside the bundle: they break `codesign --strict` and the
# signature check after download.
xattr -cr "$APP"
find "$APP" -name .DS_Store -delete
if [ -z "$TEAM_ID" ]; then
  [ -z "${SIGN_ID:-}" ] || die "no Team ID in the certificate of '$SIGN_ID'"
  codesign --force --sign - --options runtime "$APP"
  codesign --verify --deep --strict "$APP"
  echo "built $APP (ad hoc, no widgets)"
  exit 0
fi
# Developer ID needs a secure timestamp for notarization; development builds skip the round trip to Apple.
if [ "$DEVID" = 1 ]; then TS=--timestamp; else TS=--timestamp=none; fi
GROUP=$TEAM_ID.app.mauri.sukrara.poc            # macOS team-prefixed App Group: no provisioning profile needed
plutil -replace GlucoseAppGroup -string "$GROUP" "$APP/Contents/Info.plist"
ENT=build/entitlements; mkdir -p $ENT
cat > $ENT/app.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.application-groups</key><array><string>$GROUP</string></array>
</dict></plist>
EOF
if [ "${WIDGETS:-1}" != 0 ]; then
  W=../widgets-macos
  APPEX="$APP/Contents/PlugIns/SukraraWidget.appex"
  mkdir -p "$APPEX/Contents/MacOS"
  # _NSExtensionMain entry point: required, the Swift main() traps in ExtensionFoundation (docs/13).
  swiftc -sdk "$(xcrun --show-sdk-path --sdk macosx)" -target arm64-apple-macos15.0 -swift-version 5 -O \
    -parse-as-library -application-extension -module-name SukraraWidget \
    $W/Shared/*.swift $W/Widget/GlucoseWidgets.swift -Xlinker -e -Xlinker _NSExtensionMain \
    -o "$APPEX/Contents/MacOS/SukraraWidget"
  P="$APPEX/Contents/Info.plist"
  cp $W/Widget/Info.plist "$P"
  plutil -replace CFBundleIdentifier -string app.mauri.sukrara.poc.widget "$P"
  plutil -replace CFBundleExecutable -string SukraraWidget "$P"
  plutil -replace CFBundleName -string SukraraWidget "$P"
  plutil -replace CFBundleDisplayName -string Sukrara "$P"
  plutil -replace CFBundleShortVersionString -string "$(plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")" "$P"
  plutil -replace CFBundleVersion -string "$BUILD" "$P"
  plutil -replace GlucoseAppGroup -string "$GROUP" "$P"
  plutil -replace GlucoseHTTPPort -integer 0 "$P"          # App Group only; the spike host's HTTP is not used
  plutil -replace GlucoseWidgetURLScheme -string "" "$P"   # a tap just opens Sukrara (sukrara: opens "Follow…")
  cat > $ENT/widget.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.app-sandbox</key><true/>
  <key>com.apple.security.application-groups</key><array><string>$GROUP</string></array>
</dict></plist>
EOF
  xattr -cr "$APPEX"
  # Inside-out: the extension first, then the app (which seals the extension's signature into its own).
  codesign --force --sign "$SIGN_ID" --options runtime $TS --entitlements $ENT/widget.plist "$APPEX"
fi
codesign --force --sign "$SIGN_ID" --options runtime $TS --entitlements $ENT/app.plist "$APP"
codesign --verify --deep --strict "$APP"
if [ "$DEVID" = 1 ]; then
  for C in "$APP" "$APP/Contents/PlugIns/"*.appex; do
    [ -e "$C" ] || continue
    if codesign -d --entitlements - "$C" 2>/dev/null | grep -q get-task-allow; then
      die "$C has com.apple.security.get-task-allow: notarization rejects it"
    fi
  done
fi
if [ -n "${NOTARY_PROFILE:-}" ]; then
  # A zip only carries the app to the notary service (a zip can't hold a ticket); the ticket is stapled to the app.
  ZIP=build/Sukrara-notarize.zip; rm -f "$ZIP"
  ditto -c -k --keepParent "$APP" "$ZIP"
  OUT=$(xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait) || true
  echo "$OUT"
  rm -f "$ZIP"
  if ! echo "$OUT" | grep -q 'status: Accepted'; then
    SUB=$(echo "$OUT" | awk '/^ *id:/{print $2; exit}')
    [ -n "$SUB" ] && xcrun notarytool log "$SUB" --keychain-profile "$NOTARY_PROFILE" >&2
    die "notarization of $APP failed (log above)"
  fi
  xcrun stapler staple "$APP"
  xcrun stapler validate "$APP"
  spctl -a -vv "$APP"
fi
echo "built $APP (signed: $SIGN_ID, team $TEAM_ID, group $GROUP$( [ "${WIDGETS:-1}" != 0 ] && echo ', with widgets')$( [ -n "${NOTARY_PROFILE:-}" ] && echo ', notarized + stapled'))"
