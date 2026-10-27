# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# dmgbuild settings for the Sukrara installer window (dmg.sh passes -D app=… -D background=…).
# dmgbuild writes the Finder layout (.DS_Store) itself, so no Finder scripting or GUI session is needed.
import os.path

app = defines["app"]
files = [app]
symlinks = {"Applications": "/Applications"}
icon = os.path.join(app, "Contents/Resources/AppIcon.icns")   # volume icon in Finder's sidebar / desktop
background = defines["background"]                            # 640×400 pt, 1× + 2× in one TIFF

format = "UDZO"
window_rect = ((200, 140), (640, 400))
default_view = "icon-view"
show_status_bar = show_tab_view = show_toolbar = show_pathbar = show_sidebar = False
icon_size = 128
text_size = 13
# Centres line up with the arrow in background.swift (y from the top).
icon_locations = {os.path.basename(app): (170, 200), "Applications": (470, 200)}
