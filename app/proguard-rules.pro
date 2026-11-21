# Samford Patient release shrinker rules.
# kotlinx.serialization: keep generated serializers for @Serializable DTOs and navigation routes.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.samford.patient.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.samford.patient.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.samford.patient.**
-keepclassmembers class com.samford.patient.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp/Okio optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
