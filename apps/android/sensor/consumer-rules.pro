# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The Libre 3 core's JNI symbols (jni.c) are bound to Libre3Crypto's class and method names: keep both.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class app.mauri.sukrara.sensor.libre3.Libre3Crypto { *; }
