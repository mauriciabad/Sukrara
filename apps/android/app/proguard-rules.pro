# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# R8 (AGP 9 full mode) keep rules for code reached by name from native code or by reflection.

# JNA + UniFFI bindings (nostr-sdk, :share) are resolved reflectively from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.nostrdevkit.sdk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# zxing-cpp: the JNI side constructs these classes by name.
-keep class zxingcpp.** { *; }

# The Libre 3 core: jni.c's symbols are bound to this class and its method names.
-keep class app.mauri.sukrara.sensor.libre3.Libre3Crypto { *; }

# WorkManager (Glance) and our Room database are instantiated reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Glance keys its widget bookkeeping by class name: keep each widget distinct and named (R8 once merged two).
-keep,allowoptimization class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }

# Verbose and debug logs never reach a release build.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
