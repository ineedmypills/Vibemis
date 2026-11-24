# Keep annotations for runtime inspection / Compose
-keepattributes *Annotation*, Signature

# KeyMapper - keep integer VK_*/KEY_*/BTN_* constants accessed via reflection
-keep class com.ineedmypills.vibemis.utils.KeyMapper {
    public static final int *;
}

# Vibemis Rust JNI bridge (native methods & static bridge* callbacks invoked by libvibemis_core.so)
-keep class com.ineedmypills.vibemis.nvstream.jni.VibemisBridge {
    static *** bridge*(...);
    native <methods>;
}

# Okio
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**
