#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the Android release APK for a GitHub release: assembleRelease with THE release key (password from the
# Keychain, never on a command line), verify-apk.sh, then copies it to apps/android/app/build/dist/Sukrara-<version>.apk
# and archives its R8 mapping in ~/.android/sukrara-mappings/<version>-mapping.txt (crash traces from a phone are
# unreadable without it; it isn't a release asset because About lists every non-APK asset as a download).
# Refuses before tools/check/v1-complete exists. Prints the APK path on the last line.
set -eu
. "$(dirname "$0")/lib.sh"
root="$(repo_root)"
if [ ! -e "$root/tools/check/v1-complete" ]; then
  echo "refusing: tools/check/v1-complete is missing (every registry file must exist before a release)" >&2; exit 2
fi
set -- $(version_info)
version="$1"

JAVA_HOME="$(dirname "$(dirname "$(jdk_tool java)")")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SUKRARA_KEYSTORE_PASSWORD="$(release_password)" || { echo "no release key password (Keychain item sukrara-android-release)" >&2; exit 2; }
export JAVA_HOME ANDROID_HOME SUKRARA_KEYSTORE_PASSWORD

apk="$root/apps/android/app/build/outputs/apk/release/app-release.apk"
mapping="$root/apps/android/app/build/outputs/mapping/release/mapping.txt"
rm -f "$apk"   # a failed build must not leave an old APK to ship
# --no-daemon: the key's password must not stay in a long-lived Gradle daemon's environment.
(cd "$root/apps/android" && ./gradlew --no-daemon --console=plain :app:assembleRelease)
unset SUKRARA_KEYSTORE_PASSWORD
"$root/tools/release/verify-apk.sh" "$apk" "$mapping"

dist="$root/apps/android/app/build/dist"
mkdir -p "$dist" "$HOME/.android/sukrara-mappings"
cp "$apk" "$dist/Sukrara-$version.apk"
cp "$mapping" "$HOME/.android/sukrara-mappings/$version-mapping.txt"
echo "mapping archived: $HOME/.android/sukrara-mappings/$version-mapping.txt"
echo "$dist/Sukrara-$version.apk"
