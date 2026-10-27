# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Shared helpers for tools/release/*.sh and tools/install.sh (sourced, POSIX sh).

repo_root() { git -C "$(dirname "$0")" rev-parse --show-toplevel; }

# The release keystore: SUKRARA_KEYSTORE, else the default path outside the repo.
release_keystore() { echo "${SUKRARA_KEYSTORE:-$HOME/.android/sukrara-release.jks}"; }

# Its password: SUKRARA_KEYSTORE_PASSWORD, else the Keychain item made by make-keystore.sh.
release_password() {
  if [ -n "${SUKRARA_KEYSTORE_PASSWORD:-}" ]; then echo "$SUKRARA_KEYSTORE_PASSWORD"
  else security find-generic-password -s sukrara-android-release -a sukrara -w; fi
}

# A JDK tool (keytool, …): JAVA_HOME, else Homebrew's JDK 21 (macOS's /usr/bin stubs need a registered JRE).
jdk_tool() {
  for d in "${JAVA_HOME:-}" /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    if [ -n "$d" ] && [ -x "$d/bin/$1" ]; then echo "$d/bin/$1"; return 0; fi
  done
  echo "no JDK found for $1 (brew install openjdk@21, or set JAVA_HOME)" >&2; return 1
}

# An Android SDK build tool (apksigner, aapt2, …), newest build-tools first.
sdk_tool() {
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  t="$(ls -d "$sdk"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -1)"
  if [ -z "$t" ]; then echo "no $1 in $sdk/build-tools" >&2; return 1; fi
  echo "$t"
}

# Upper-case hex SHA-256 of a keystore entry's certificate (no colons), like apksigner's "certificate SHA-256 digest".
# Reads the store password from SUKRARA_KS_PW.
cert_sha256() {
  "$1" -exportcert -keystore "$2" -alias "$3" -storepass:env SUKRARA_KS_PW | openssl dgst -sha256 | awk '{print toupper($NF)}'
}

# "0.90.0 90000 prerelease|release" from VERSION: versionCode = major·1 000 000 + minor·1 000 + patch.
version_info() {
  v="$(tr -d ' \n' < "$(repo_root)/VERSION")"
  case "$v" in
    *[!0-9.]* | *..* | .* | *.) echo "VERSION '$v' is not MAJOR.MINOR.PATCH" >&2; return 1 ;;
  esac
  IFS=. read -r major minor patch <<EOF
$v
EOF
  if [ -z "$patch" ] || [ "$minor" -ge 1000 ] || [ "$patch" -ge 1000 ]; then echo "VERSION '$v': minor and patch must be < 1000" >&2; return 1; fi
  kind=release; [ "$major" -eq 0 ] && kind=prerelease
  echo "$v $((major * 1000000 + minor * 1000 + patch)) $kind"
}
