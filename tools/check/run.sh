#!/bin/sh
# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# The repo's deterministic guards (CLAUDE.md rules → checks). Run before committing; the hooks in tools/hooks run it
# automatically (tools/hooks/install.sh), CI runs it on every push / PR.
#   tools/check/run.sh                      origin/main..HEAD (the branch), strict
#   tools/check/run.sh --range A..B         any range, strict
#   tools/check/run.sh --staged             the index: static rules fail, coupling rules remind
#   tools/check/run.sh --pending            also list the files the registries name that don't exist
#   tools/check/run.sh --help               what each check does
#   tools/check/run.sh --layout             the cross-surface tests (one graph everywhere, freshness): Android
#                                           :core:graph tests (JDK 21) and the Mac GraphTests
#   tools/check/run.sh --selftest           the checks' own tests (tools/check/selftest.sh)
root="$(git rev-parse --show-toplevel)"
if [ "$1" = "--layout" ]; then
  set -e
  if [ -f "$root/apps/android/settings.gradle.kts" ]; then
    (cd "$root/apps/android" && ./gradlew -q :core:graph:test)
  else
    echo "apps/android: no Gradle build yet, skipped"
  fi
  if [ -f "$root/apps/macos/Package.swift" ]; then
    (cd "$root/apps/macos" && swift test -q --filter GraphTests)
  else
    echo "apps/macos: no Swift package yet, skipped"
  fi
  exit 0
fi
if [ "$1" = "--selftest" ]; then
  exec sh "$root/tools/check/selftest.sh"
fi
exec python3 -I "$(dirname "$0")/check.py" "$@"
