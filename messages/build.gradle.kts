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
    id("com.google.devtools.ksp")
}

android {
    defaultConfig {
        versionCode = 20260603
        versionName = "v2.5.1"
        applicationId = "com.vayunmathur.messages"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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

    // Network — for any auxiliary HTTP we end up needing outside the
    // dedicated RPC client.
    implementation(project(":library:network"))

    // Bouncy Castle — AES-IGE for MTProto encryption, X25519 for WhatsApp Noise
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Signal protocol crypto (Double Ratchet, sealed sender, pre-keys, etc.)
    implementation("org.signal:libsignal-android:0.86.5")

    // OkHttp — WebSocket transport for Signal
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx.serialization — session data persistence
    implementation(libs.kotlinx.serialization.json)
}
