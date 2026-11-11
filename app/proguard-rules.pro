-keep class id.iqbal.tiktokauto.AutoScrollService { *; }
-keep class id.iqbal.tiktokauto.MainActivity { *; }
-keep class id.iqbal.tiktokauto.HandScrollService { *; }

# MediaPipe: JNI C++ membaca field/method Java lewat nama asli
# (mis. field platform_ pada MediaPipeLoggingProto$SystemInfo).
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
