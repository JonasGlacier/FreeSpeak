# Keep JNI native method classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }

# sherpa-onnx JNI also looks up Kotlin configuration fields and result constructors.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.freespeak.engine.itn.** { *; }
