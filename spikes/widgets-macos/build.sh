#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds GlucoseWidgets.app (headless host) with GlucoseWidget.appex (WidgetKit) inside, using only the
# Command Line Tools: swiftc + manual bundle layout + codesign + lsregister/pluginkit.
#   ./build.sh            build into build/
#   ./build.sh install    also copy to ~/Applications, register, and (re)start the host
#   ./build.sh render     render every widget family to evidence/*.png (no WidgetKit host needed)
set -euo pipefail
cd "${0:A:h}"
SDK=$(xcrun --show-sdk-path --sdk macosx)
TARGET=arm64-apple-macos15.0
OUT=build
APP=$OUT/GlucoseWidgets.app
APPEX=$APP/Contents/PlugIns/GlucoseWidget.appex
LSREG=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
SWIFTC=(swiftc -sdk "$SDK" -target $TARGET -swift-version 5 -Onone)

if [[ "${1:-}" == render ]]; then
  mkdir -p $OUT evidence
  $SWIFTC -parse-as-library -module-name Render Shared/*.swift Render/RenderMain.swift -o $OUT/render
  $OUT/render evidence
  exit 0
fi

if [[ "${1:-}" == probe ]]; then
  # The widget's loader in a sandboxed CLI (same entitlements, ad-hoc): which transports work?
  # PROBE_ID=dev.spike.GlucoseWidgets.widget runs it *as* the widget (same sandbox container).
  mkdir -p $OUT
  PID_=${PROBE_ID:-dev.spike.GlucoseWidgets.probe}
  cp Probe/Info.plist $OUT/probe-Info.plist && plutil -replace CFBundleIdentifier -string $PID_ $OUT/probe-Info.plist
  $SWIFTC -module-name Probe Shared/GlucoseData.swift Shared/SukraraGraph.swift Shared/Durations.swift Shared/AlertRules.swift Shared/SettingsSpec.swift Shared/WearerSettings.swift Shared/Loader.swift Probe/main.swift -o $OUT/probe \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker $OUT/probe-Info.plist
  codesign --force --sign - -i $PID_ --entitlements ${WIDGET_ENT:-Widget/Widget.entitlements} $OUT/probe
  $OUT/probe
  exit 0
fi

rm -rf $APP
mkdir -p $APP/Contents/MacOS $APPEX/Contents/MacOS

# Widget extension. -application-extension restricts to extension-safe API. The entry point must be
# Foundation's _NSExtensionMain (as Xcode links extensions): it sets up the extension runtime and then runs the
# @main WidgetBundle. Starting at the Swift main() instead traps in _EXRunningExtension._shared (docs/13).
$SWIFTC -parse-as-library -application-extension -module-name GlucoseWidget \
  Shared/*.swift Widget/GlucoseWidgets.swift -o $APPEX/Contents/MacOS/GlucoseWidget \
  -Xlinker -e -Xlinker _NSExtensionMain
cp Widget/Info.plist $APPEX/Contents/Info.plist

# Host app.
$SWIFTC -module-name GlucoseWidgets Shared/GlucoseData.swift Shared/SukraraGraph.swift Shared/Durations.swift Shared/AlertRules.swift Shared/SettingsSpec.swift Shared/WearerSettings.swift Host/main.swift -o $APP/Contents/MacOS/GlucoseWidgets
cp Host/Info.plist $APP/Contents/Info.plist

# Signing, inside-out. Default: the first "Apple Development" identity in the keychain (Xcode → Settings →
# Accounts → Manage Certificates; a free Apple ID personal team is enough). chronod only loads widgets whose
# signature carries a Team ID, see docs/13. No identity (or AD_HOC=1) → ad hoc, which builds but never loads.
#   SIGN_ID=<sha1|name> SIGN_KEYCHAIN=<path>  sign with another identity (e.g. a self-signed cert)
#   WIDGET_ENT=Widget/Widget-minimal.entitlements   NO_RUNTIME=1   variants used in the investigation
if [[ -z "${SIGN_ID:-}" && "${AD_HOC:-}" != 1 ]]; then
  SIGN_ID=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/{print $2; exit}')
fi
TEAM_ID=${TEAM_ID:-}
if [[ -z "$TEAM_ID" && -n "${SIGN_ID:-}" && "$SIGN_ID" != - ]]; then
  TEAM_ID=$(security find-certificate -c "$SIGN_ID" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F' = ' '/organizationalUnitName/{print $2; exit}')
fi
# macOS team-prefixed App Group: the host and widget share it with no provisioning profile.
GROUP=${TEAM_ID:+$TEAM_ID.dev.spike.glucosewidgets}; GROUP=${GROUP:-group.dev.spike.glucosewidgets}
echo "signing: ${SIGN_ID:-ad hoc}  team: ${TEAM_ID:-none}  group: $GROUP"
for plist in $APPEX/Contents/Info.plist $APP/Contents/Info.plist; do
  plutil -replace GlucoseAppGroup -string $GROUP $plist
done
sed "s/group\.dev\.spike\.glucosewidgets/$GROUP/" ${WIDGET_ENT:-Widget/Widget.entitlements} > $OUT/widget.entitlements
sed "s/group\.dev\.spike\.glucosewidgets/$GROUP/" Host/Host.entitlements > $OUT/host.entitlements
RT=(--options runtime); [[ "${NO_RUNTIME:-}" == 1 ]] && RT=()
SIGN=(--sign "${SIGN_ID:--}" --timestamp=none); [[ -n "${SIGN_KEYCHAIN:-}" ]] && SIGN+=(--keychain "$SIGN_KEYCHAIN")
codesign --force $SIGN $RT --entitlements $OUT/widget.entitlements $APPEX
codesign --force $SIGN $RT --entitlements $OUT/host.entitlements $APP
codesign --verify --deep --strict $APP && echo "signed OK" || echo "codesign --verify failed (expected for untrusted certs)"

if [[ "${1:-}" == install ]]; then
  DEST=$HOME/Applications/GlucoseWidgets.app
  pkill -f "$DEST/Contents/MacOS/GlucoseWidgets" && sleep 1 || true
  rm -rf $DEST && cp -R $APP $DEST
  $LSREG -f -R $DEST
  pluginkit -a $DEST/Contents/PlugIns/GlucoseWidget.appex || true
  open $DEST
  sleep 2
  pluginkit -m -v -p com.apple.widgetkit-extension | grep -i glucose || echo "pluginkit: not registered"
fi
