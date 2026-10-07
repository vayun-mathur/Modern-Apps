import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("common-conventions-app")
    // Store-listing screenshots rendered from Compose @Preview (car views).
    id("common-conventions-preview-metadata")
    id("com.google.devtools.ksp")
}

launcherIcon {
    symbol = "call"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.communicate"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            // libsignal-client bundles its desktop JNI natives (macOS .dylib,
            // Windows .dll) as Java resources. They can never load on Android and
            // add ~40 MB to the APK — strip them. The Android lib/arm64-v8a/
            // libsignal_jni.so is unaffected.
            excludes += setOf("**/*.dylib", "*.dylib", "**/*.dll", "*.dll")
        }
        jniLibs {
            // Test-only libsignal native (NativeTesting bridge); unused in prod.
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

// ----------------------------------------------------------------
// Protobuf code generation (manual)
// ----------------------------------------------------------------
//
// Both `com.squareup.wire` and `com.google.protobuf` Gradle plugins fail
// to apply to this module because they try to cast AGP 9's
// `ApplicationExtensionImpl` to the legacy `BaseExtension`, which AGP 9
// removed. Until those plugins catch up, we resolve `protoc` from Maven
// Central and invoke it directly. The resulting Java classes (lite
// runtime) are added to the variant's Java sources via the AGP
// `androidComponents` extension below.

val osClassifier: String = run {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        osName.contains("win") -> "windows"
        else -> "linux"
    }
    val cpu = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("64") -> "x86_64"
        else -> "x86_32"
    }
    "$os-$cpu"
}

val protocConfig: Configuration = configurations.create("protocBinary") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    "protocBinary"("${libs.protobuf.protoc.get()}:$osClassifier@exe")
}

val protoSrcDir = layout.projectDirectory.dir("src/main/proto")
val protoGenDir = layout.buildDirectory.dir("generated/source/proto/java")

// Custom task class so we can inject ExecOperations cleanly (the
// configuration cache rejects capturing the Project at execution time).
abstract class GenerateProtoTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val protocBinary: RegularFileProperty

    @get:InputDirectory
    abstract val protoSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val binary = protocBinary.get().asFile
        binary.setExecutable(true)
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val srcDir = protoSourceDir.get().asFile
        val protoFiles = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "proto" }
            .toList()
        if (protoFiles.isEmpty()) return
        exec.exec {
            commandLine = buildList {
                add(binary.absolutePath)
                add("--java_out=${out.absolutePath}")
                add("-I=${srcDir.absolutePath}")
                addAll(protoFiles.map { it.absolutePath })
            }
        }
    }
}

val generateProto = tasks.register<GenerateProtoTask>("generateProto") {
    protocBinary.set(layout.file(protocConfig.elements.map { it.single().asFile }))
    protoSourceDir.set(protoSrcDir)
    outputDir.set(protoGenDir)
}

// Make Kotlin/Java/KSP compilation wait on protoc. KSP runs ahead of
// compileKotlin so it has to be in this list too.
tasks.matching {
    it.name.startsWith("compile") &&
        (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac")) ||
    it.name.startsWith("ksp")
}.configureEach {
    dependsOn(generateProto)
}

androidComponents {
    onVariants { variant ->
        // Wire proto gen sources + Rust JNI libs into each variant.
        variant.sources.java?.addStaticSourceDirectory(protoGenDir.get().asFile.absolutePath)
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
    }
}

// Classic Signal protocol v3 (X3DH + Double Ratchet + Sender Keys) for the WhatsApp
// primary client — Rust impl. Unique crate name to avoid workspace collision with
// messages' whatsapp_signal.
rustNativeLib("communicate_signal", "communicate_signal")

// MLS (RFC 9420, OpenMLS) for the RCS line's closed-loop E2EE — separate crate
// (own dependency tree) with its own JNI lib.
rustNativeLib("communicate_mls", "communicate_mls", srcDir = "src/main/rust-mls")

dependencies {
    // Google Voice virtual line: protojson RPCs + SIP-over-WSS transport go through
    // the repo's own Android-only HTTP/WebSocket stack (no OkHttp/Ktor).
    implementation(project(":library:network"))
    // Local Compose image loading for Google Voice MMS previews (remote https + content://).
    implementation(project(":library:image"))
    // RingRTC supplies both Signal's calling engine and the org.webrtc classes every line's calling
    // uses. It bundles its own WebRTC, so no other WebRTC distribution can be on the classpath: the two
    // ship ~368 identical org.webrtc class names, and RingRTC's native library binds to that exact
    // package via JNI symbol names and FindClass descriptors, so relocating it is not an option either.
    implementation(libs.ringrtc.android)
    // E.164 normalization to reconcile SIM vs Google Voice numbers.
    implementation(libs.libphonenumber)
    // Compile-only stubs for the framework's @SystemApi app-data backup transport
    // (see :library:backup-stubs).
    compileOnly(project(":library:backup-stubs"))
    // Compile-only stubs for the framework's @SystemApi single-registration RCS APIs
    // (SipDelegateManager, UCE). Absent from the public SDK; the framework provides
    // them at runtime, so they must NOT be packaged. See :library:rcs-stubs.
    compileOnly(project(":library:rcs-stubs"))
    // Persist the Google Voice / WhatsApp session (cookies, API key, auth, number).
    implementation(libs.androidx.datastore.preferences)
    // Document-start JS injection to hook fetch/XHR before the GV web app captures them.
    implementation(libs.androidx.webkit)

    // ---- WhatsApp primary client ----
    // Room - E2E state + message/thread/reaction/poll cache (communicate's first Room DB).
    implementRoom(libs)
    implementation(project(":library:room"))

    // Protobuf runtime — full Java variant. This module only touches ByteString and
    // InvalidProtocolBufferException, both of which javalite also has, so the switch to lite
    // is feasible; it is deferred because it regenerates all 11 Signal/WhatsApp protos and
    // changes toString() and unknown-field semantics on live messaging paths.
    implementation(libs.protobuf.java)

    // ZXing core — QR code encoding only (no scanner UI). Retained for parity with the
    // messages port; unused by the primary registration flow.
    implementation(libs.zxing.core)

    // Signal protocol crypto (Double Ratchet, sealed sender, pre-keys, etc.).
    // Also provides certificate verification (ECPublicKey.verifySignature) for the WhatsApp
    // Noise handshake cert chain.
    implementation(libs.libsignal.android)

    // kotlinx.serialization — session/auth data persistence + serviceData blob.
    implementation(libs.kotlinx.serialization.json)

    // Car templates (P-car-dock): AndroidX Car App Library, same pairing as
    // :maps. Only pulled into the car code path (service/car/) — phone UI,
    // sync services and crypto paths are untouched.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
