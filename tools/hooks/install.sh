#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Use tools/hooks for this clone and all its worktrees (core.hooksPath is shared repo config).
set -e
cd "$(git rev-parse --show-toplevel)"
chmod +x tools/hooks/pre-commit tools/hooks/commit-msg tools/check/run.sh
git config core.hooksPath tools/hooks
echo "hooks installed: core.hooksPath=$(git config core.hooksPath)"
