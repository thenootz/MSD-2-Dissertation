# Pavlova ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Rust JNI bridge
-keep class com.pavlova.ml.RustMLBridge {
    native <methods>;
}

# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Compose
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep model classes (if using data classes for Room)
-keep class com.pavlova.data.model.** { *; }

# Keep service classes
-keep class com.pavlova.services.** { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Security Crypto
-keep class androidx.security.crypto.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
