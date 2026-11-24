# NodRoid R8 / ProGuard keep rules.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class dev.nodroid.model.** { *; }
-keep class dev.nodroid.engine.** { *; }
-keep class dev.nodroid.database.** { *; }
# kotlinx.serialization: keep generated serializers.
-keepclassmembers class **$$serializer { *; }
-keep class kotlinx.serialization.json.** { *; }
# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
# QuickJS (dokar3) — native bindings are loaded reflectively.
-keep class com.dokar.quickjs.** { *; }
# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
# Koin
-keep class org.koin.** { *; }
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
