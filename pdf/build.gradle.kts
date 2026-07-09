import java.util.Properties

plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.pdf"
    }
    // The Rust build drops <abi>/libpdf_render.so under this dir; register it as
    // a jniLibs source so AGP packages the native lib.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs").get().asFile)
}

// ---------------------------------------------------------------------------
// Native PDF renderer (Rust + lopdf). See pdf/rust/.
//
// Powers the "Open PDF (safe)" viewer: parses PDFs entirely in memory-safe Rust
// and returns plain drawing primitives, never touching the system PDF stack.
// Cross-compiled per-ABI with the NDK's clang, driven directly from cargo (same
// approach as :weather).
//
// Contributor/CI prerequisite:
//   rustup target add aarch64-linux-android
// arm64 is the device/release ABI; the release build filters to arm64-v8a (see
// the build convention). Apple-Silicon emulators are arm64 too.
// ---------------------------------------------------------------------------

val ndkVersionForRust = "29.0.14206865"
val androidApiLevel = 31

fun resolveSdkDir(): String =
    System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
        ?: error("Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)")

val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
val ndkRoot = "${resolveSdkDir()}/ndk/$ndkVersionForRust"
// The NDK ships an x86_64 host toolchain (runs under Rosetta on Apple Silicon).
val hostTag = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
    else -> error("Unsupported host OS for NDK toolchain")
}
val ndkBin = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
val ndkSysroot = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot"

// (jniLibs ABI dir, Rust target triple)
// Only arm64-v8a: the dev build type inherits the convention's
// `abiFilters = ["arm64-v8a"]` (same as release), so any other ABI would be
// filtered out of the APK anyway. To support Intel-host emulators, add
// `"x86_64" to "x86_64-linux-android"` here and drop the abiFilter for dev.
val rustAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android",
)

val perAbiBuildTasks = rustAbis.map { (abiDir, triple) ->
    tasks.register<Exec>("cargoBuild_${abiDir.replace('-', '_')}") {
        description = "Cross-compiles the Rust PDF renderer for $abiDir."
        workingDir = file("rust")

        val clang = "$ndkBin/$triple$androidApiLevel-clang"
        val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
        val soOut = file("rust/target/$triple/release/libpdf_render.so")
        val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/libpdf_render.so").get().asFile

        inputs.dir("rust/src")
        inputs.file("rust/Cargo.toml")
        outputs.file(destSo)

        // Prepend the rustup toolchain (has the Android targets) ahead of any
        // Homebrew rust on PATH.
        environment("PATH", "$cargoBin:${System.getenv("PATH")}")
        // The per-API NDK clang wrapper bakes in --target and the sysroot.
        environment("CC", clang)
        environment("AR", "$ndkBin/llvm-ar")
        environment("SYSROOT", ndkSysroot)
        environment(linkerVar, clang)
        // Keep host-side C tooling (if any build dep needs it) on the host clang.
        environment("HOST_CC", "/usr/bin/clang")

        commandLine("$cargoBin/cargo", "build", "--release", "--target", triple)

        doLast {
            destSo.parentFile.mkdirs()
            soOut.copyTo(destSo, overwrite = true)
        }
    }
}

val cargoNdkBuild by tasks.registering {
    description = "Builds libpdf_render.so for all Android ABIs."
    dependsOn(perAbiBuildTasks)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild)
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.material)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    implementation(project(":library:ocr"))
}
