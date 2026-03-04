# Add project specific ProGuard rules here.
-keep class com.taskwarrior.android.data.** { *; }
-keep class com.taskwarrior.android.sync.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
