# Add project specific ProGuard rules here.

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep Room entities
-keep class com.fauxx.data.db.** { *; }
-keep class com.fauxx.targeting.layer1.UserDemographicProfile { *; }
-keep class com.fauxx.targeting.layer2.PlatformProfileCache { *; }
-keep class com.fauxx.targeting.layer3.PersonaHistoryEntity { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Gson models
-keep class com.fauxx.data.model.SyntheticPersona { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
