# SafeSignal R8/ProGuard rules.
#
# Rules here exist for correctness (evidence must still decrypt and verify
# after an upgrade) and for safety (no reflective access to secret material).

# Room generates implementations reflectively referenced by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class com.safesignal.core.database.** { *; }

# kotlinx.serialization style manual reflection (see data:local models).
-keepclassmembers class com.safesignal.** {
    <init>(...);
    <fields>;
}
-keep class com.safesignal.data.models.** { *; }

# Never let R8 rename or strip crypto provider names referenced by string.
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# OkHttp / Okio platform shims.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**