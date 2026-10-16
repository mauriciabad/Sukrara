# JNA + UniFFI bindings are resolved reflectively from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.nostrdevkit.sdk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# zxing-cpp: the JNI side constructs these Kotlin classes by name.
-keep class zxingcpp.** { *; }
# WorkManager (used by Glance widgets) creates its Room database reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# Glance keys its widget bookkeeping by the GlanceAppWidget class name. R8 horizontally merged GraphWidget into
# CombinedWidget (same obfuscated class), so after a process restart the "Glucose + graph" widget was rendered as
# the plain graph (gap audit 2026-10-16). Keep each widget class distinct and named.
-keep,allowoptimization class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }
