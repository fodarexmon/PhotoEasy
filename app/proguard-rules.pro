# Keep kotlinx.serialization annotations and generated code
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep PhotoEasy data models from minification/reflection issues
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }



# --- R8 for the "dist" build ---------------------------------------------------------------
# The app's own code is kept whole (it is small next to the libraries R8 trims, and some of it
# is looked up by name: enums from saved projects, JSON keys, Compose previews).
-keep class com.example.** { *; }
-keepclassmembers enum * { *; }
# LiteRT (TensorFlow Lite) uses JNI.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# ML Kit / Play services ship their own rules; keep quiet about optional classes.
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# ML Kit face detection (bundled) talks to its native detector through internal protobuf-lite
# classes that are read by reflection. R8 full mode renamed / merged them, and the detector then
# found no faces at all (Liquify "Smart face", Smart Auto). Keep ML Kit and its internals whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-keep class com.google.android.gms.common.internal.** { *; }
