# JNA + UniFFI bindings are resolved reflectively from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class rust.nostr.sdk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
