# Keep TAGa-Say model classes if serialized in future
-keep class com.tagasay.app.data.model.** { *; }
-keep class com.tagasay.app.data.network.** { *; }
-keep class com.tagasay.core.crypto.** { *; }

# libsignal uses JNI and reflection
-keep class org.signal.libsignal.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn org.signal.**
-dontwarn com.sun.jna.**

# ZXing QR
-keep class com.google.zxing.** { *; }

-dontwarn org.slf4j.**
