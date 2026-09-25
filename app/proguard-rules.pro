# TensorFlow Lite is accessed directly; keep native bindings available.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
