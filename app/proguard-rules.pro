# ONNX Runtime Java classes are called from the native JNI layer.
# Keep the API classes, constructors, and method signatures intact so JNI
# reflection/lookup cannot be broken by R8 shrinking or obfuscation.
-keep class ai.onnxruntime.** { *; }
