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
set -e
cd "$(dirname "$0")"
swift build -c release
APP=build/Sukrara.app
ICON=../brand/assets/sukrara/macos/AppIcon.icns
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/GlucoseBar "$APP/Contents/MacOS/Sukrara"
cp "$ICON" "$APP/Contents/Resources/AppIcon.icns"
# Alert sounds (spikes/alerts/sounds/out, IMA4 CAF, 4 packs × 7 kinds × 3 variants): UNNotificationSound(named:)
# looks in the app's Library/Sounds, a shared group container's Library/Sounds, then the main bundle — the bundle
# keeps them with the app (and its signature) instead of writing into ~/Library/Sounds.
cp ../alerts/sounds/out/*.caf "$APP/Contents/Resources/"
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
  <key>CFBundleShortVersionString</key><string>0.11.1</string>
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

# Desktop / Notification Center widgets (spikes/widgets-macos, docs/13). macOS only loads a widget signed with a
# Team ID, so they are built only when an Apple-issued identity is in the keychain (Xcode → Settings → Accounts →
# Manage Certificates → Apple Development; a free personal team works on this Mac, a Developer ID for others).
# Without one (or with AD_HOC=1) the app is ad hoc signed with no widget, as before.
#   SIGN_ID=<sha1|name>  pick the identity     WIDGETS=0  sign with it but leave the widget out
if [ -z "${SIGN_ID:-}" ] && [ "${AD_HOC:-}" != 1 ]; then
  SIGN_ID=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development|Developer ID Application/{print $2; exit}')
fi
TEAM_ID=""
if [ -n "${SIGN_ID:-}" ]; then
  TEAM_ID=$(security find-certificate -c "$SIGN_ID" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F' = ' '/organizationalUnitName/{print $2; exit}')
fi
if [ -z "$TEAM_ID" ]; then
  codesign --force --sign - "$APP"
  echo "built $APP (ad hoc, no widgets)"
  exit 0
fi
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
  codesign --force --sign "$SIGN_ID" --options runtime --entitlements $ENT/widget.plist "$APPEX"
fi
codesign --force --sign "$SIGN_ID" --entitlements $ENT/app.plist "$APP"
codesign --verify --strict "$APP"
echo "built $APP (signed: $SIGN_ID, team $TEAM_ID, group $GROUP$( [ "${WIDGETS:-1}" != 0 ] && echo ', with widgets'))"
