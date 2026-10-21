#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# The repo's deterministic guards (CLAUDE.md rules → checks). Run before committing; the hooks in tools/hooks run it
# automatically (tools/hooks/install.sh), CI runs it on every push / PR.
#   tools/check/run.sh                      origin/main..HEAD (the branch), strict
#   tools/check/run.sh --range A..B         any range, strict
#   tools/check/run.sh --staged             the index: static rules fail, coupling rules remind
#   tools/check/run.sh --help               what each check does
#   tools/check/run.sh --layout             the cross-surface tests (one graph everywhere, freshness): Android
#                                           CardLayoutTest + FreshnessFixtureTest (needs JDK 21) and the Mac -graphselftest
if [ "$1" = "--layout" ]; then
  set -e
  root="$(git rev-parse --show-toplevel)"
  (cd "$root/spikes/showcase-android" &&
    JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}" \
    GALLERY_JAVA_HOME="${GALLERY_JAVA_HOME:-$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home}" \
    ./gradlew -q :app:testDebugUnitTest --tests '*CardLayoutTest*' --tests '*FreshnessFixtureTest*' --tests '*GraphFixtureTest*')
  (cd "$root/spikes/menubar" && swift build -q && .build/debug/GlucoseBar -graphselftest)
  exit 0
fi
exec python3 -I "$(dirname "$0")/check.py" "$@"
