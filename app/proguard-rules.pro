# R8 / ProGuard optimization rules for IPO Guruji
# Keeps obfuscation percentage high while protecting runtime serialization,
# Navigation Compose type-safe routes, and persisted Room/DataStore enums.

# Preserve readable line numbers for Play Console crash deobfuscation
# while hiding original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep kotlinx.serialization serializers & descriptors (required under R8 full mode
# for Room JSON TypeConverters, DataStore JSON, and Navigation Compose @Serializable routes)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable model & route classes' Companion/serializer methods and fields
-keep,includedescriptorclasses class com.example.data.model.**$$serializer { *; }
-keepclassmembers class com.example.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.example.data.preferences.GmpWatchRule$$serializer { *; }
-keepclassmembers class com.example.data.preferences.GmpWatchRule {
    *** Companion;
}
-keepclasseswithmembers class com.example.data.preferences.GmpWatchRule {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose type-safe @Serializable routes require their route class & serializer kept
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class com.example.ui.navigation.** { *; }
-keep,includedescriptorclasses class com.example.ui.navigation.**$$serializer { *; }

# Keep persisted enum names stored via .name / valueOf() in Room and DataStore
-keepclassmembers enum com.example.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.example.data.preferences.ThemePreference {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
