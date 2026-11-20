# VdHelper and Bridge are started by name from this APK: `app_process / com.adetkov.droid.VdHelper ...` (see
# vd-start.sh) and `app_process / com.adetkov.droid.Bridge` (see AdbHelper).
-keep class com.adetkov.droid.VdHelper { public static void main(java.lang.String[]); }
-keep class com.adetkov.droid.Bridge { public static void main(java.lang.String[]); }

# sherpa-onnx's native code reads its config classes' fields and calls its Java classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The text-to-speech callback: native code calls invoke([F)Ljava/lang/Integer; on it (see Speech.synthesize).
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Integer invoke(float[]);
}
