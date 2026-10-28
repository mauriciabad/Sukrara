# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# nostr-sdk is UniFFI-generated Kotlin over a Rust .so loaded through JNA: JNA finds structures, callbacks and
# native methods by reflection, so neither may be renamed or removed by R8 (tools/release/android-smoke.sh).
-keep class org.nostrdevkit.sdk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
