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
-keep class net.zetetic.database.sqlcipher.** { *; }

# Error-prone annotations are compile-time only; missing at runtime is expected
-dontwarn com.google.errorprone.annotations.**

# LAN sync (E13 #178): JNA reflectively accesses its mapped classes and native
# callback structures, and lazysodium binds to libsodium through JNA. R8 would
# otherwise strip/rename these and crash only in the minified release build.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.*

# LAN sync Gson + Room models (kept so R8 cannot rename the fields Gson reflects
# over for the wire schema, nor the paired_peers entity columns).
-keep class com.fauxx.sync.wire.PairingPayload { *; }
-keep class com.fauxx.sync.data.PairedPeerEntity { *; }
