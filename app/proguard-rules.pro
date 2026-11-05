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

