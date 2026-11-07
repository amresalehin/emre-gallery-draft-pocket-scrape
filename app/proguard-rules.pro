# Proguard rules for Emre Gallery

# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Tesseract 4 Android JNI
-keep class com.googlecode.tesseract.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Coil
-dontwarn coil.**

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }
