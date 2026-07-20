import java.util.Properties

plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "partly_cloudy_day"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.weather"
    }
    // The Rust build drops <abi>/libweather_om.so under this dir; register it as
    // a jniLibs source so AGP packages the native lib like the existing
    // libmaplibre.so.
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// Native `.om` decoder (Rust). See weather/src/main/rust/.
//
// Cross-compiled per-ABI with the NDK's clang. We drive cargo directly (rather
// than rust-android-gradle, which needs AGP's removed legacy AppExtension, or
// cargo-ndk, which sets prefixed CC_<target> env that the om-file-format-sys
// build script ignores — it only honours an unprefixed CC + SYSROOT).
//
// Contributor/CI prerequisite:
//   rustup target add aarch64-linux-android x86_64-linux-android
// arm64 is the device/release ABI; x86_64 covers the emulator for dev. The
// release build filters to arm64-v8a (see the build convention).
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
// `abiFilters = ["arm64-v8a"]` (same as release), so an x86_64 lib would be
// filtered out of the APK anyway. Apple-Silicon emulators are arm64 too. To
// support Intel-host emulators, add `"x86_64" to "x86_64-linux-android"` here
// and drop the abiFilter for the dev build type.
val rustAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android",
)

val perAbiBuildTasks = rustAbis.map { (abiDir, triple) ->
    tasks.register<Exec>("cargoBuild_${abiDir.replace('-', '_')}") {
        description = "Cross-compiles the Rust .om decoder for $abiDir."
        workingDir = file("src/main/rust")

        val clang = "$ndkBin/$triple$androidApiLevel-clang"
        val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
        val soOut = file("src/main/rust/target/$triple/release/libweather_om.so")
        val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/libweather_om.so").get().asFile

        inputs.dir("src/main/rust/src")
        inputs.file("src/main/rust/Cargo.toml")
        outputs.file(destSo)

        // Prepend the rustup toolchain (has the Android targets) ahead of any
        // Homebrew rust on PATH.
        environment("PATH", "$cargoBin:${System.getenv("PATH")}")
        // The om-file-format-sys build script uses an unprefixed CC + SYSROOT;
        // the per-API NDK clang wrapper bakes in --target and the sysroot.
        environment("CC", clang)
        environment("AR", "$ndkBin/llvm-ar")
        environment("SYSROOT", ndkSysroot)
        environment(linkerVar, clang)
        // Keep host-side C tooling (if any build dep needs it) on the host clang.
        environment("HOST_CC", "/usr/bin/clang")
        // bindgen parses the C headers with libclang: point it at the NDK too.
        environment(
            "BINDGEN_EXTRA_CLANG_ARGS",
            "--target=$triple$androidApiLevel --sysroot=$ndkSysroot",
        )

        commandLine("$cargoBin/cargo", "build", "--release", "--target", triple)

        doLast {
            destSo.parentFile.mkdirs()
            soOut.copyTo(destSo, overwrite = true)
        }
    }
}

val cargoNdkBuild = tasks.register("cargoNdkBuild") {
    description = "Builds libweather_om.so for all Android ABIs."
    dependsOn(perAbiBuildTasks)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.reorderable)
    implementation(libs.maplibre.compose)
    implementRoom(libs)
    implementation(project(":library:room"))
}
