#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks a V1 release APK before it reaches a phone or a GitHub release:
#   tools/release/verify-apk.sh <apk> [mapping.txt]
# - signature verifies and the signer certificate is THE release key (tools/release/android-cert.sha256)
# - package app.mauri.sukrara, versionName / versionCode = tools/release/version.sh, not debuggable
# - the Libre 3 native core lib/arm64-v8a/libsukrara_libre3.so is inside
# - no debug code: the merged manifest names no DebugReceiver, and (R8 renames classes, so the dex names prove
#   nothing) the R8 mapping, when given, lists no DebugReceiver / DebugFakes / Synthetic* class
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"
apk="${1:?usage: verify-apk.sh <apk> [mapping.txt]}"
mapping="${2:-}"
fail=0
bad() { echo "FAIL $*"; fail=1; }
ok() { echo "ok   $*"; }

apksigner="$(sdk_tool apksigner)"
aapt2="$(sdk_tool aapt2)"
want_cert="$(cat "$here/android-cert.sha256")"
set -- $(version_info)
want_name="$1" want_code="$2"

certs="$("$apksigner" verify --print-certs "$apk" 2>&1)" || { bad "signature doesn't verify: $certs"; certs=""; }
signers="$(printf '%s\n' "$certs" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr 'a-f' 'A-F' | sort -u)"
if [ "$signers" = "$want_cert" ]; then ok "signed by the release key"; else bad "signer '$signers' is not the release key $want_cert"; fi

badging="$("$aapt2" dump badging "$apk")"
pkg="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
code="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p")"
name="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")"
[ "$pkg" = app.mauri.sukrara ] && ok "package $pkg" || bad "package '$pkg' (want app.mauri.sukrara)"
[ "$code" = "$want_code" ] && ok "versionCode $code" || bad "versionCode '$code' (want $want_code from VERSION)"
[ "$name" = "$want_name" ] && ok "versionName $name" || bad "versionName '$name' (want $want_name)"
if printf '%s\n' "$badging" | grep -q "application-debuggable"; then bad "the APK is debuggable"; else ok "not debuggable"; fi

if unzip -l "$apk" | grep -q 'lib/arm64-v8a/libsukrara_libre3.so$'; then ok "Libre 3 native core present"
else bad "lib/arm64-v8a/libsukrara_libre3.so missing (third_party/Juggluco not built in)"; fi

manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
if printf '%s\n' "$manifest" | grep -Eq 'DebugReceiver|DebugFakes|Synthetic'; then bad "the manifest registers debug components"
else ok "manifest has no debug components"; fi

if [ -n "$mapping" ]; then
  if grep -Eq '^[^ ].*\.(DebugReceiver|DebugFakes|Synthetic[A-Za-z]*) ->' "$mapping"; then bad "R8 mapping lists debug classes"
  else ok "R8 mapping lists no debug classes"; fi
else
  echo "note no R8 mapping given: debug classes only checked through the manifest"
fi
exit $fail
