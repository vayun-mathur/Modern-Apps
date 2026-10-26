-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontobfuscate

# Annotation metadata is read reflectively by Room, kotlinx.serialization and
# protobuf-lite, so these stay repo-wide. MethodParameters is deliberately NOT
# here: it stores a name string for every parameter of every method in the app,
# and no app reflects on parameter names.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature

# Protobuf Lite - the generated runtime schema accesses message fields (e.g.
# platform_) reflectively, so R8 must not strip them. Without this you get
# "Field platform_ for ...SystemInfo not found" at runtime in release builds.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Classes that Rust resolves by literal name over JNI ---
# The rule above only covers a class that DECLARES a native method. These are
# looked up from Rust with FindClass/GetMethodID against a hardcoded string while
# declaring no native members of their own, so renaming them breaks the lookup.
# Worse, most degrade silently instead of throwing: jni_http::init just returns
# false, and callers treat that as "no network" — maps renders blank tiles and
# youpipe returns error envelopes, both logging a missing-dependency message that
# points nowhere near the real cause.

# library/jni-http/src/main/rust/src/lib.rs:78,121 — every .so reaches the network
# through this one bridge, so this covers maps, youpipe, weather, ...
# Both methods must be kept: `request` serves single range reads (archive header
# open, sub-two-range batches) and `requestBatch` serves every tile wave of two
# or more ranges. `requestBatch` is only referenced from Rust over JNI, so R8
# sees no Java-side caller and strips it in release builds — which fails every
# tile with "Method not found: requestBatch" and renders a blank map (issue 792).
-keep class com.vayunmathur.library.network.NativeHttpBridge {
    public static byte[] request(int, java.lang.String, byte[], byte[]);
    public static byte[] requestBatch(java.lang.String, java.lang.String[]);
}

# maps/src/main/rust/src/lib.rs:138 — call_method(thiz, "fetchTrafficData", ...).
# OfflineRouter itself has native methods so its name survives, but this is a
# plain private method and its name does not.
-keepclassmembers class com.vayunmathur.maps.util.OfflineRouter {
    private void fetchTrafficData(double, double, double, double, int, boolean);
}

# maps/src/main/rust/src/lib.rs:234,575,765 — FindClass + new_object on these two
# nested result types. Neither declares a native method.
-keep class com.vayunmathur.maps.util.OfflineRouter$RawStep { <init>(...); }
-keep class com.vayunmathur.maps.util.OfflineRouter$RawDeparture { <init>(...); }

# euicc/src/main/rust/src/jni.rs:29 — call_static_method("transmitApdu", "([B)[B").
-keep class com.vayunmathur.euicc.EuiccNative {
    public static byte[] transmitApdu(byte[]);
}

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

# gRPC + okhttp + protobuf-lite (:appstore Accrescent source). grpc-okhttp is the first
# okhttp in the repo. grpc looks up its transport/name-resolver providers via reflection and
# META-INF/services; okhttp/okio and grpc reference optional compile-time deps (Conscrypp,
# error-prone, javax.annotation, animal-sniffer) that are absent at runtime on Android.
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
# Accrescent's generated protobuf-lite messages (app.accrescent.appstore.v1, com.android.bundle)
# are covered by the GeneratedMessageLite keep above; grpc service descriptors are under io.grpc.

# logback-classic (transitive via KeePassJava2-dom, used by :passwords) is a
# server-side SLF4J backend that references the Servlet API. javax.servlet.*
# doesn't exist on Android, so R8 flags the dangling reference. logback's
# servlet integration is never used on Android; suppress the missing refs.
-dontwarn javax.servlet.**
-dontwarn ch.qos.logback.classic.servlet.**

# JGit (org.eclipse.jgit, used by :code) is a desktop/server Java library. It references JVM
# APIs absent on Android — JMX (java.lang.management / javax.management), GSS-API/Kerberos
# (org.ietf.jgss), java.lang.ProcessHandle, and the SLF4J static binder — only on code paths
# Android never hits (MBean monitoring, Negotiate HTTP auth, PID-file locks). With
# android.enableR8.fullMode=true these dangling refs fail the build, so suppress them.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.**

# --- Room migrations — keep reflective Companion access (see SqlCipher.kt:93, FFDatabase.kt:160) ---
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements com.vayunmathur.library.util.DatabaseMigrations { *; }
# SqlCipher.kt:93 calls dbClass.getDeclaredField("Companion"), and dbClass is
# always a RoomDatabase subclass — so this only has to cover those rather than
# every class in every app. The keep above already retains all members of a
# RoomDatabase subclass; spelling it out keeps the requirement visible if that
# rule is ever tightened.
-keepclassmembers class * extends androidx.room3.RoomDatabase { *** Companion; }
-keep class androidx.room3.migration.Migration { *; }

# --- Glance widgets — keep classes instantiated reflectively ---
# Glance instantiates ActionCallbacks via
# Class.forName(name).getDeclaredConstructor().newInstance(), and GenericWidgetWorker
# (library/widgets Widgets.kt) does the same for GlanceAppWidget subclasses. The
# reified generics at the call sites (actionRunCallback<FooAction>()) keep the class
# names, but R8 sees no direct constructor call and strips the no-arg <init> — every
# tap then dies with "NoSuchMethodException: FooAction.<init> []" and Glance logs
# "BroadcastReceiver execution failed". Calculator's keypad widget hit exactly this:
# taps vibrated (launcher touch feedback) but onAction never ran, so nothing typed.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidget {
    public <init>();
}
