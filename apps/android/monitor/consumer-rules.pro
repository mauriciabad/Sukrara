# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The root slot daemon is started by name through app_process (systemui/RootStatusBar): keep its main.
-keep class app.mauri.sukrara.monitor.systemui.SlotDaemon { public static void main(java.lang.String[]); }
# LSPosed loads the module by the name in META-INF/xposed/java_init.list and calls its no-argument constructor.
-keep class app.mauri.sukrara.monitor.systemui.SystemUiHook { <init>(); }
# The libxposed API is provided by LSPosed at run time (compileOnly).
-dontwarn io.github.libxposed.api.**
