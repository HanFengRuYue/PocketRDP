# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Room
-keep class androidx.room.** { *; }

# FreeRDP JNI (M2+)
-keep class com.freerdp.freerdpcore.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
