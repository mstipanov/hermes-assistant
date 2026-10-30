# R8 rules for the release/beta builds.
#
# sherpa-onnx is a JNI library: the native side looks classes and methods up by name,
# so nothing under its package may be renamed or stripped.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep readable stack traces (line numbers) without shipping the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization and Compose ship their own consumer rules; the app adds none of its own.
