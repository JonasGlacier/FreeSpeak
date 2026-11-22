# Keep JNI native method classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
