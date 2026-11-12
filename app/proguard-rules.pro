# RouXin-shellToolBox ProGuard rules

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Termux terminal components (referenced from XML and reflection-safe)
-keep class com.termux.** { *; }

# Keep native method bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
