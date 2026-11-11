-keep class id.iqbal.tiktokauto.AutoScrollService { *; }
-keep class id.iqbal.tiktokauto.MainActivity { *; }
-keep class id.iqbal.tiktokauto.HandScrollService { *; }

# MediaPipe Tasks + Framework: semua kelas, field, method, dan inner class
# harus dipertahankan karena JNI native mengaksesnya lewat nama literal.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keepclassmembers class com.google.mediapipe.** {
    native <methods>;
    <fields>;
}
-keepclassmembers class com.google.mediapipe.framework.** {
    native <methods>;
    <fields>;
}

# Protobuf yang digunakan MediaPipe secara internal
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Google common libraries yang dipakai MediaPipe
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**

-dontwarn com.google.mediapipe.**
