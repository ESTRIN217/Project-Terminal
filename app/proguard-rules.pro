# Keep Termux emulator and view classes (used via reflection/interfaces)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
