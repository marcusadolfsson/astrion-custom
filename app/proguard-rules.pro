# R8 rules for the release build.
#
# The app is small and reflection-light, but three libraries need protection
# from aggressive shrinking/obfuscation:
#   - kotlinx.serialization: generated serializers are looked up reflectively,
#     so their synthetic members must survive.
#   - OkHttp/Okio: known optional-class warnings on Android.
#   - Compose: mostly R8-friendly, but keep the runtime's reflective bits.

# ---- kotlinx.serialization ------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# Keep `INSTANCE.serializer()` / companion serializers of @Serializable types.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.custom.astrion.**$$serializer { *; }
-keepclassmembers class com.custom.astrion.** {
    *** Companion;
    *** serializer(...);
}
-keep class kotlinx.serialization.** { *; }

# ---- OkHttp / Okio --------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Kotlin / coroutines --------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# ---- App ------------------------------------------------------------------
# Card renderers are instantiated directly (no reflection), so they can be
# shrunk/renamed freely. Keep the Application + Activity entry points, which
# the manifest references by name.
-keep class com.custom.astrion.AstrionApp { *; }
-keep class com.custom.astrion.MainActivity { *; }

# The input bridge is an ENTRY POINT reached only from a shell:
#   CLASSPATH=<apk> app_process /system/bin com.custom.astrion.bridge.InputBridge
# Nothing in the app references it, so R8 would strip it and the command would
# fail with ClassNotFoundException at a moment when debugging means adb.
-keep class com.custom.astrion.bridge.** { *; }
