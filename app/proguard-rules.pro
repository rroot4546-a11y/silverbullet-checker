# Proguard rules
-keepattributes Signature
-keepattributes *Annotation*

-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
