#!/bin/sh
# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# The checks' own tests: stage throwaway files in a TEMPORARY index (the real index and work tree are never touched)
# and assert what tools/check/check.py --staged says about them. Run: tools/check/run.sh --selftest (CI rules job).
set -u
root="$(git rev-parse --show-toplevel)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# Each case starts from a copy of the real index (what you are about to commit; in CI the checkout), and only the
# FAIL lines the case adds over that baseline count.
real_index="$(git -C "$root" rev-parse --path-format=absolute --git-path index)"
export GIT_INDEX_FILE="$tmp/index"
fails=0
cp "$real_index" "$GIT_INDEX_FILE"
python3 -I "$root/tools/check/check.py" --staged --only static 2>&1 | grep '^FAIL' > "$tmp/baseline"

# case <name> <expect: fail|pass> <grep pattern for fail> <path> <content>
case_() {
  name="$1" expect="$2" pattern="$3" path="$4" content="$5"
  cp "$real_index" "$GIT_INDEX_FILE"
  blob="$(printf '%s\n' "$content" | git -C "$root" hash-object -w --stdin)"
  git -C "$root" update-index --add --cacheinfo 100644 "$blob" "$path"
  out="$(python3 -I "$root/tools/check/check.py" --staged --only static 2>&1 | grep '^FAIL' | grep -vxF -f "$tmp/baseline")"
  if [ "$expect" = fail ]; then
    if printf '%s' "$out" | grep -q "^FAIL \[$pattern\]"; then echo "ok   $name"; else echo "FAIL $name (expected FAIL [$pattern])"; printf '%s\n' "$out"; fails=$((fails + 1)); fi
  else
    if printf '%s' "$out" | grep -q "^FAIL"; then echo "FAIL $name (expected no failure)"; printf '%s\n' "$out"; fails=$((fails + 1)); else echo "ok   $name"; fi
  fi
}

HDR='// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later'

case_ "Kotlin without header fails" fail header apps/android/x/src/main/kotlin/A.kt 'fun a() = 1'
case_ "hardcoded UI text fails" fail strings apps/android/x/src/main/kotlin/A.kt "$HDR
@Composable fun A() { Text(\"hi there\") }"
case_ "same under poc/ passes" pass "" poc/x/src/main/kotlin/A.kt '@Composable fun A() { Text("hi there") }'
case_ "a debug fake in src/main fails" fail debug apps/android/app/src/main/kotlin/app/mauri/sukrara/DebugFakes.kt "$HDR
object DebugFakes"
case_ "a debug fake in src/debug passes" pass "" apps/android/app/src/debug/kotlin/app/mauri/sukrara/DebugFakes.kt "$HDR
object DebugFakes"
case_ "src/main naming DebugReceiver fails" fail debug apps/android/app/src/main/kotlin/app/mauri/sukrara/App.kt "$HDR
val r = DebugReceiver()"
case_ "Mac debugsend outside #if DEBUG fails" fail debug apps/macos/Sources/SukraraApp/Launch.swift "$HDR
let flag = \"-debugsend\"
func debugsend() {}"
case_ "Mac debugsend inside #if DEBUG passes" pass "" apps/macos/Sources/SukraraApp/Launch.swift "$HDR
#if DEBUG
func debugsend() {}
#else
let x = 1
#endif"
case_ "a hand-written DataStore key fails" fail settings apps/android/core/data/src/main/kotlin/app/mauri/sukrara/Store.kt "$HDR
val k = booleanPreferencesKey(\"graphHours\")"
case_ "a hand-written defaults key fails" fail settings apps/macos/Sources/SukraraCore/Store.swift "$HDR
let v = UserDefaults.standard.integer(forKey: \"graphHours\")"
case_ "a cleartext URL in app Kotlin fails" fail cleartext apps/android/integrations/src/main/kotlin/app/mauri/sukrara/N.kt "$HDR
val u = \"http://example.org\""
case_ "an image outside the allowed folders fails" fail images apps/android/x/shot.png 'not really a png'
case_ "an image in the frozen PoC passes" pass "" poc/spikes/brand/assets/x/icon.png 'not really a png'
case_ "an nsec in app code fails" fail secrets apps/android/x/src/main/kotlin/K.kt "$HDR
val k = \"nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq\""

if [ "$fails" -gt 0 ]; then echo "$fails self-test case(s) failed"; exit 1; fi
echo "all check self-tests passed"
