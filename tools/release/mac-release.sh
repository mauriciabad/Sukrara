#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the Mac release bundle, dmg and zip for a GitHub prerelease (bundle.sh + verify-app.sh). A stub until the
# release chunk (27) fills it in; it refuses to run before tools/check/v1-complete exists (until then the registries'
# pending V1 files only produce NOTEs).
set -eu
. "$(dirname "$0")/lib.sh"
if [ ! -e "$(repo_root)/tools/check/v1-complete" ]; then
  echo "refusing: tools/check/v1-complete is missing (every registry file must exist before a release)" >&2; exit 2
fi
echo "not implemented yet (chunk 27)" >&2
exit 2
