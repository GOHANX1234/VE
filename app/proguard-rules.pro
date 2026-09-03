# Proguard / R8 Optimization & Obfuscation Rules for VE Sandbox

# Preserve annotations, signatures, line numbers for debugging
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Ignore compiler warnings for third-party libraries
-dontwarn androidx.**
-dontwarn org.jetbrains.kotlin.**
-dontwarn com.google.crypto.tink.**

# HiddenApiBypass - uses Unsafe and deep reflection for hidden API restrictions
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# Virtual Engine Sandbox - Core Engine & Reflection Hooks
# Keep all public, protected, and package-private classes, interfaces, methods and fields in sandbox core
-keep class com.ve.sandbox.core.** { *; }
-keep interface com.ve.sandbox.core.** { *; }
-dontwarn com.ve.sandbox.core.**

# Android Components declared in AndroidManifest.xml (must keep exact names)
-keep class com.ve.sandbox.VeApplication { *; }
-keep class com.ve.sandbox.ui.** { *; }
-keep class com.ve.sandbox.core.stub.** { *; }

# Standard Android Component Preservations
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgent

# Dynamic proxy and reflection hook member preservation
-keepclassmembers class * {
    *** *Stub*(...);
}

# Preserve native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Serializable & Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
