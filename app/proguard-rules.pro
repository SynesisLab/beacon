# Beacon release shading rules (M0 baseline; tightened per-feature as milestones land).

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep,includedescriptorclasses class dev.beacon.**$$serializer { *; }
-keepclassmembers class dev.beacon.** {
    *** Companion;
}
-keepclasseswithmembers class dev.beacon.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Conductor of the stream: keep the wire-level classes intact
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**