# Keep TDLib JNI bindings — they are referenced by native code via reflection/JNI.
-keep class org.drinkless.tdlib.** { *; }
