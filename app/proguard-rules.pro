# Keep the Linphone JNI surface: native code calls these reflectively.
-keep class org.linphone.** { *; }
-dontwarn org.linphone.**

# Tink (via androidx.security:security-crypto) references error-prone annotations
# that are compile-only; they never appear at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# kotlinx.serialization keeps serializers via generated serializersModule lookups.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.patbaumgartner.android.stringtelephone.**$$serializer { *; }
-keepclassmembers class com.patbaumgartner.android.stringtelephone.** { *** Companion; }
-keepclasseswithmembers class com.patbaumgartner.android.stringtelephone.** { kotlinx.serialization.KSerializer serializer(...); }

