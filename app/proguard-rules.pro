# Paper Critic Release R8 / ProGuard Keep Rules
# Preserve stack trace line numbers for crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Firestore & Data Models (reflection/POJO/enum serialization)
-keep class com.example.data.model.** { *; }
-keepclassmembers enum com.example.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room Database Entities, DAOs, and Generated Impl
-keep class com.example.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# PDFBox-Android font/encoding & parser classes
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.bouncycastle.**

# Firebase AI Logic, Auth, Firestore, App Check, Credentials
-keep class com.google.firebase.** { *; }
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.firebase.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
