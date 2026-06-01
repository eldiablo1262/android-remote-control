# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep our service classes
-keep class com.remote.control.** { *; }

# Keep accessibility service
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
