#!/bin/sh
# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the Mac release for a GitHub release: Bundle/bundle.sh (signed by team QWUV9H4A3G; SIGN_ID picks the
# identity), verify-app.sh, Bundle/dmg.sh, then verifies the dmg's signature. Leaves apps/macos/build/Sukrara-<version>.dmg
# and Sukrara-mac-<version>.zip. NOTARY_PROFILE=<profile> notarizes + staples (needs a Developer ID identity; without
# it Gatekeeper asks once: System Settings › Privacy & Security › Open Anyway).
# Refuses unless tools/check/strict exists. Prints the dmg and zip paths on the last two lines.
set -eu
. "$(dirname "$0")/lib.sh"
root="$(repo_root)"
if [ ! -e "$root/tools/check/strict" ]; then
  echo "refusing: tools/check/strict is missing (every registry file must exist before a release)" >&2; exit 2
fi
set -- $(version_info)
version="$1"
build="$root/apps/macos/build"

"$root/apps/macos/Bundle/bundle.sh"
"$root/tools/release/verify-app.sh" "$build/Sukrara.app"
"$root/apps/macos/Bundle/dmg.sh"
dmg="$build/Sukrara-$version.dmg"
zip="$build/Sukrara-mac-$version.zip"
codesign --verify --strict "$dmg"
[ -s "$zip" ] || { echo "no $zip" >&2; exit 1; }
if [ -n "${NOTARY_PROFILE:-}" ]; then xcrun stapler validate "$dmg"
else echo "not notarized (no NOTARY_PROFILE): Gatekeeper asks once on first open (Open Anyway)"; fi
echo "$dmg"
echo "$zip"
