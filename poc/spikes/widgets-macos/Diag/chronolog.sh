#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# usage: Diag/chronolog.sh [seconds]
# What chronod (the WidgetKit daemon) and ExtensionKit say about our extension, plus our own widget/host logs.
/usr/bin/log show --last ${1:-60}s --style compact --predicate '(process == "chronod" AND (eventMessage CONTAINS "entitled" OR eventMessage CONTAINS "purging" OR eventMessage CONTAINS[c] "glucose" OR eventMessage CONTAINS[c] "dev.spike")) OR subsystem == "dev.spike.GlucoseWidgets" OR process == "GlucoseWidget"' 2>&1 | cut -c1-${2:-300}
