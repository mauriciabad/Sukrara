#!/bin/sh
# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# ONE-TIME: creates Sukrara's Android release key (RSA 4096, valid 30 years) outside the repo and stores its random
# password in the macOS Keychain. Every release APK is signed with it; Android only installs an update signed by
# the same key, so LOSING IT = NO MORE UPDATES for installed apps. Back it up right after (docs/20-publishing.md).
#   tools/release/make-keystore.sh          create it (refuses when the keystore or the Keychain item exists)
# Writes tools/release/android-cert.sha256 (the certificate's public SHA-256, committed; verify-apk.sh checks it).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"
ks="$(release_keystore)"
service=sukrara-android-release
alias=sukrara

if [ -e "$ks" ]; then
  echo "refusing: $ks already exists (never overwrite the release key)" >&2; exit 1
fi
if security find-generic-password -s "$service" -a "$alias" >/dev/null 2>&1; then
  echo "refusing: the Keychain already has a '$service' password (an older key?). Find its keystore first." >&2; exit 1
fi
keytool="$(jdk_tool keytool)"
mkdir -p "$(dirname "$ks")"
# The password never goes on a command line (argv is visible to other processes): keytool reads it from the
# environment, security -i reads its command from stdin.
SUKRARA_KS_PW="$(openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-40)"
export SUKRARA_KS_PW
trap 'rm -f "$ks"' EXIT  # a half-made key is removed; disarmed once the Keychain has the password
"$keytool" -genkeypair -keystore "$ks" -storetype PKCS12 -alias "$alias" -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Sukrara, O=Maurici Abad" -storepass:env SUKRARA_KS_PW -keypass:env SUKRARA_KS_PW 2>/dev/null
printf 'add-generic-password -s %s -a %s -l "Sukrara Android release key" -w %s\n' "$service" "$alias" "$SUKRARA_KS_PW" \
  | security -i >/dev/null
security find-generic-password -s "$service" -a "$alias" >/dev/null
trap - EXIT
chmod 600 "$ks"
cert_sha256 "$keytool" "$ks" "$alias" > "$here/android-cert.sha256"
unset SUKRARA_KS_PW
echo "created $ks (alias $alias); password in the Keychain item '$service'"
echo "certificate SHA-256: $(cat "$here/android-cert.sha256")"
echo
echo "BACK IT UP NOW (docs/20-publishing.md): copy $ks and the Keychain password into your password manager."
