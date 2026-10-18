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
exec python3 -I "$(dirname "$0")/check.py" "$@"
