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
    id("common-conventions-metadata")
    id("com.google.devtools.ksp")
}

launcherIcon {
    symbol = "sms"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.messages"
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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

val protocVersion = "4.28.3"

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
    "protocBinary"("com.google.protobuf:protoc:$protocVersion:$osClassifier@exe")
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
        variant.sources.java?.addStaticSourceDirectory(protoGenDir.get().asFile.absolutePath)
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(project(":library:room"))

    // Protobuf runtime — full Java variant. The lite runtime drops the
    // reflection API which we need for the PB-Lite encoder.
    implementation(libs.protobuf.java)

    // ZXing core — QR code encoding only (no scanner UI). We render the
    // pairing QR ourselves in a native Compose composable.
    implementation(libs.zxing.core)

    // Ktor (transitive from :library:network is implementation-scoped
    // so we need to declare directly).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Coil — loads device-contact photo URIs (content://) into the
    // conversation-row avatars in InboxScreen / ConversationScreen.
    implementation(libs.coil.compose)

    // CameraX — built-in capture fallback when no system camera app
    // handles ACTION_IMAGE_CAPTURE (see ui/CameraCaptureScreen.kt).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // OkHttp — WebSocket transport for Signal
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Bouncy Castle — AES-IGE for MTProto encryption, X25519 for WhatsApp Noise
    implementation(libs.bouncycastle)

    // Signal protocol crypto (Double Ratchet, sealed sender, pre-keys, etc.)
    implementation("org.signal:libsignal-android:0.86.5")
    // Classic pure-Java Signal protocol (X3DH) for the WhatsApp bridge. libsignal-android 0.86
    // removed X3DH (PQXDH-only), which WhatsApp companion sessions require. This artifact has no
    // native lib and a different package (org.whispersystems.libsignal.*), so it coexists with
    // libsignal-android (used by the Signal bridge) without conflict.
    // Classic Signal protocol (X3DH) for the WhatsApp bridge, with its protobuf 3.10 relocated
    // into a private package (see :whatsapp-signal) so it does not collide with the app's
    // protobuf 4.x. libsignal-android 0.86 (Signal bridge) cannot decrypt WhatsApp's X3DH pkmsgs.
    implementation(project(mapOf("path" to ":whatsapp-signal", "configuration" to "shaded")))

    // OkHttp — WebSocket transport for Signal
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx.serialization — session data persistence
    implementation(libs.kotlinx.serialization.json)
}
