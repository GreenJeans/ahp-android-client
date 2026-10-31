# R8 / ProGuard rules for ahp-android-client release builds

# Keep kotlinx.serialization generated serializers and Companion.serializer() methods
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Agent Host Protocol (com.microsoft.agenthostprotocol) wire types and their serializers
-keep class com.microsoft.agenthostprotocol.** { *; }
-keep,includedescriptorclasses class com.microsoft.agenthostprotocol.**$$serializer { *; }

# Keep any @Serializable classes in dev.ahpclient (e.g. SessionListCache, DirectHostCodec)
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.ahpclient.**$$serializer { *; }

# Keep WorkManager worker constructors
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp / Okio platform reflection warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
