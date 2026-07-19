-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontobfuscate

# JavaMail / Jakarta Mail — providers are loaded via reflection and META-INF/javamail.providers
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }

# Tesseract
-keep class com.googlecode.tesseract.android.** { *; }

# LiteRT LM / Gemma 4
-keep class com.google.ai.edge.litertlm.** { *; }

# OpenAssistant tools: the @Tool/@ToolParam-annotated methods in the ToolSet are
# never called from Kotlin directly — litertlm discovers and invokes them via
# reflection (using method + parameter names to build the function schema). R8's
# shrinker would treat them as unused and remove them, silently breaking every
# assistant tool. Keep the ToolSet implementation (all members + names) and any
# @Tool method, plus the attributes reflection relies on.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,MethodParameters,Signature
-keep class com.vayunmathur.openassistant.util.AssistantToolSet { *; }
-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# LiteRT Core - prevent R8 from deleting LiteRT classes used via reflection
-keep class com.google.ai.edge.litert.** { *; }

# Protobuf Lite - the generated runtime schema accesses message fields (e.g.
# platform_) reflectively, so R8 must not strip them. Without this you get
# "Field platform_ for ...SystemInfo not found" at runtime in release builds.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# MediaPipe (tasks-vision) - relies on the protobuf classes above and JNI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Flogger (FluentLogger) - MediaPipe logs through it. forEnclosingClass() walks
# the call stack to find the caller; R8 optimization merges/inlines Flogger's
# internal classes which breaks the walk ("no caller found on the stack for ...
# FluentLogger"). Keep Flogger intact so the stack-walk works.
-keep class com.google.common.flogger.** { *; }
-keep class com.google.common.flogger.backend.** { *; }
-keep class com.google.common.flogger.backend.system.** { *; }
-dontwarn com.google.common.flogger.**

-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime (com.microsoft.onnxruntime:onnxruntime-android) — the native .so
# creates Java objects and calls their constructors/methods/fields via JNI (e.g.
# ai.onnxruntime.TensorInfo). R8 can't see those JNI uses, so in minified release
# builds it strips the JNI-only members, causing crashes like
# "NoSuchMethodError: no non-static method Lai/onnxruntime/TensorInfo;.<init>([J[Ljava/lang/String;I)V".
# Keep the whole ORT API surface (classes + all members) so nothing it needs is removed.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Stockfish (com.github.vayun-mathur:Stockfish-Library, used by :games:chess) — the
# native libstockfish.so calls back into Kotlin via JNI. nativeSetOutputCallback looks
# up Stockfish$OutputCallback.onOutput(String) by name via GetMethodID, and the lambda
# passed to Stockfish.init { } is compiled to a Stockfish$init$1 OutputCallback SAM.
# R8 optimization (horizontal class merging) reroutes that SAM into an unrelated class,
# so the JNI lookup fails with "NoSuchMethodError: no non-static method
# Lkotlin/time/Duration$Companion;.onOutput(Ljava/lang/String;)V". Keep the whole
# Stockfish API + callback interface so the JNI callback resolves.
-keep class com.vayunmathur.stockfish.** { *; }
-keep interface com.vayunmathur.stockfish.** { *; }
-dontwarn com.vayunmathur.stockfish.**

# BouncyCastle (post-quantum crypto for Office): keep providers/algorithms found via reflection.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# logback-classic (transitive via KeePassJava2-dom, used by :passwords) is a
# server-side SLF4J backend that references the Servlet API. javax.servlet.*
# doesn't exist on Android, so R8 flags the dangling reference. logback's
# servlet integration is never used on Android; suppress the missing refs.
-dontwarn javax.servlet.**
-dontwarn ch.qos.logback.classic.servlet.**
