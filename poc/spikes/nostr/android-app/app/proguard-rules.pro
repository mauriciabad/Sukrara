# JNA + UniFFI bindings are resolved reflectively from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.nostrdevkit.sdk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# zxing-cpp: the JNI side constructs these Kotlin classes by name.
-keep class zxingcpp.** { *; }
