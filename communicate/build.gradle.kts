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

// Catalog coordinate (libs.protobuf.protoc) with the platform-classified
// exe artifact selected programmatically instead of a string coordinate
// with an appended classifier.
val protocDependency =
    dependencies.create(libs.protobuf.protoc.get()) as org.gradle.api.artifacts.ExternalModuleDependency
protocDependency.artifact {
    classifier = osClassifier
    type = "exe"
}

dependencies {
    "protocBinary"(protocDependency)
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

// Only the classes below actually load a native library: they call the
// Rust wrappers (RustSignalCrypto / RustMlsCrypto / RustWhatsAppCrypto,
// all loading communicate_signal / communicate_mls via NativeLoader),
// the Signal/WhatsApp/RCS E2E facades over them (SignalE2E, WhatsAppE2E,
// RcsE2E), libsignal's native-backed protocol/zkgroup classes directly
// (IdentityKeyPair.generate, ClientZkGroupCipher, SealedSessionCipher),
// or the key-generation facades over those (SignalRegistrationKeys,
// RegistrationKeys, SignalPqPreKey, WhatsAppPqPreKey,
// RegistrationAttestation). Quarantine them into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.communicate.data.Y3MergedGvTest",
            "com.vayunmathur.communicate.data.Y3RcsSendSuccessTest",
            "com.vayunmathur.communicate.rcs.MlsLifecycleRcsPass3Test",
            "com.vayunmathur.communicate.rcs.RcsSlice3E2ESupportTest",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2E2ETest",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2FakeTransportTest",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2UiTest",
            "com.vayunmathur.communicate.signal.RegistrationHttpClientSignalPass3Test",
            "com.vayunmathur.communicate.signal.RustSignalCryptoSignalPass3Test",
            "com.vayunmathur.communicate.signal.SignalAuthAndServiceDataTest",
            "com.vayunmathur.communicate.signal.SignalCallAndTransportParseTest",
            "com.vayunmathur.communicate.signal.SignalCdsiTest",
            "com.vayunmathur.communicate.signal.SignalGroupsAndSyncTest",
            "com.vayunmathur.communicate.signal.SignalIdentitySignalPass3Test",
            "com.vayunmathur.communicate.signal.SignalInboundPollW1Test",
            "com.vayunmathur.communicate.signal.SignalSafetyNumberTest",
            "com.vayunmathur.communicate.signal.SignalSealedSenderTest",
            "com.vayunmathur.communicate.signal.SignalSlice2E2ETest",
            "com.vayunmathur.communicate.signal.SignalSlice2InboundTest",
            "com.vayunmathur.communicate.signal.SignalSlice2MessagingOfflineTest",
            "com.vayunmathur.communicate.signal.SignalSlice2SealedCacheTest",
            "com.vayunmathur.communicate.signal.Y1SignalMessagingGroupTest",
            "com.vayunmathur.communicate.signal.Y1SignalSendPipelineTest",
            "com.vayunmathur.communicate.signal.Y2SignalSocketRegTest",
            "com.vayunmathur.communicate.signal.Y3SignalGroupOpsServerTest",
            "com.vayunmathur.communicate.signal.Y3SignalPipelineServerTest",
            "com.vayunmathur.communicate.signal.Y3SignalServerApiTest",
            "com.vayunmathur.communicate.telephony.WelcomeJoinRcsPass3Test",
            "com.vayunmathur.communicate.ui.Y3GroupCreatePickerTest",
            "com.vayunmathur.communicate.whatsapp.W5Slice1AttestationTest",
            "com.vayunmathur.communicate.whatsapp.W5Slice1MexRouterTest",
            "com.vayunmathur.communicate.whatsapp.W5Slice1RegBundleTest",
            "com.vayunmathur.communicate.whatsapp.WhatsAppAttestationTest",
            "com.vayunmathur.communicate.whatsapp.WhatsAppRegistrationBadParamRegressionTest",
            "com.vayunmathur.communicate.whatsapp.Y1WaE2eSyncTest",
            "com.vayunmathur.communicate.whatsapp.Y1WaInboundTest",
            "com.vayunmathur.communicate.whatsapp.Y1WaRegistrationHttpTest",
            "com.vayunmathur.communicate.whatsapp.Y2WaSocketSendTest",
            "com.vayunmathur.communicate.whatsapp.Y3AppStatePageTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // these touch the process-wide DataStoreUtils singleton (the
    // datastore_default file behind GoogleVoiceSession and the line
    // sessions) or collect its flows, so persisted DataStore state can
    // leak into classes expecting defaults when batched. Audit rule:
    // any test instantiating a *LineSession / GoogleVoiceSession or
    // using DataStoreUtils.getInstance directly belongs here. All other
    // classes batch.
    //
    // Post-batch CI fallout (46858d7faa, 5-CPU) added six more:
    // (The fixed P-CSCF port 5060 shared by the DirectSip/Ims tests is
    // handled in those classes with SO_REUSEADDR + bounded bind retry:
    // forkEvery is a per-worker restart counter, not a fixed batch, so
    // no task placement can serialize them.)
    // - RcsSlice3Pass2DirectSipAuthTest asserts the RcsDirectSip
    //   singleton's SIP CSeq starts at 1; batched neighbours that drive
    //   the same singleton (same Robolectric sandbox classloader)
    //   increment it first ("CSeq: 7 REGISTER" observed).
    // - RcsSlice3Pass2TransportTest asserts RcsCapabilityExchange's
    //   updates flow starts empty; neighbours populate it.
    // - RcsSlice3Pass2TlsAndFtTest holds RcsMsrpTls's process-wide
    //   cached identity + persisted identity files and the
    //   RcsDatabase.overrideForTest seam.
    // - W5Pass2ClientCoreTest / W5Pass2SyncHistoryTest /
    //   W5Pass2ContactUtilsTest drive the WhatsAppClient singleton
    //   (auth/db/socket/state fields and its never-cancelled IO scope),
    //   whose asynchronous leftovers from batched neighbours can land
    //   inside their assertions.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.communicate.data.SecretSealingTest",
            "com.vayunmathur.communicate.data.Y1RepositoryLoadersTest",
            "com.vayunmathur.communicate.googlevoice.GoogleVoiceSessionSlice4Test",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2DirectSipAuthTest",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2TlsAndFtTest",
            "com.vayunmathur.communicate.rcs.RcsSlice3Pass2TransportTest",
            "com.vayunmathur.communicate.ui.Y2TopLevelScreensTest",
            "com.vayunmathur.communicate.whatsapp.W5Pass2ClientCoreTest",
            "com.vayunmathur.communicate.whatsapp.W5Pass2ContactUtilsTest",
            "com.vayunmathur.communicate.whatsapp.W5Pass2SyncHistoryTest",
        )
    )
}

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
    // and single-registration RCS APIs (SipDelegateManager, UCE). Absent from the
    // public SDK; the framework provides them at runtime, so they must NOT be
    // packaged. See :library:aosp-stubs.
    compileOnly(project(":library:aosp-stubs"))
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

    // Voicemail tab audio playback: plain ExoPlayer (no session/controller stack —
    // voicemail clips are short local files, not a browsable media library).
    implementation(libs.androidx.media3.exoplayer)

    // voicemailNumber() reads TelephonyManager.voiceMailNumber, which the unit-test stub
    // android.jar does not implement ("not mocked"). Robolectric supplies the framework
    // shadows so the pilot lookup (and its permission/dual-SIM fallbacks) can be asserted
    // on the JVM; same reason emergency/pdf use it.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.car.app.testing)
    testImplementation(libs.androidx.test.core)
    // Compile-only hidden-API stubs (SipMessage etc.) so RCS inbound tests can reference
    // them; Robolectric's android-all supplies the real implementations at runtime.
    testImplementation(project(":library:aosp-stubs"))
    // Route slice-6 contract tests use KClass reflection (sealedSubclasses, constructors).
    testImplementation(libs.kotlin.reflect)
    // RCS slice tests: real in-memory Room database via the bundled sqlite driver
    // (same pattern as :todo's TodoMigrationTest).
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}
