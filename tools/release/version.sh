#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Prints "VERSION versionCode prerelease|release" from the root VERSION file (versionCode = major·1 000 000 +
# minor·1 000 + patch; 0.x = prerelease). Both apps and the release scripts read the version this way.
set -eu
. "$(dirname "$0")/lib.sh"
version_info
