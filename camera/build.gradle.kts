import java.util.Properties

plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "photo_camera"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.camera"
    }
    // The Rust stitcher drops <abi>/libcamera_stitch.so under this dir; register
    // it as a jniLibs source so AGP packages the native lib.
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// Native panorama stitcher + night burst aligner (Rust). See camera/src/main/rust/.
// Cross-compiled per-ABI with the NDK's clang, driven directly from cargo (same
// approach as :pdf and :weather).
// Contributor/CI prerequisite:  rustup target add aarch64-linux-android
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
val hostTag = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
    else -> error("Unsupported host OS for NDK toolchain")
}
val ndkBin = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
val ndkSysroot = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot"

// (jniLibs ABI dir, Rust target triple)
val rustAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android",
)

val perAbiBuildTasks = rustAbis.map { (abiDir, triple) ->
    tasks.register<Exec>("cargoBuild_${abiDir.replace('-', '_')}") {
        description = "Cross-compiles the Rust camera stitcher for $abiDir."
        workingDir = file("src/main/rust")

        val clang = "$ndkBin/$triple$androidApiLevel-clang"
        val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
        val soOut = file("src/main/rust/target/$triple/release/libcamera_stitch.so")
        val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/libcamera_stitch.so").get().asFile

        inputs.dir("src/main/rust/src")
        inputs.file("src/main/rust/Cargo.toml")
        outputs.file(destSo)

        environment("PATH", "$cargoBin:${System.getenv("PATH")}")
        environment("CC", clang)
        environment("AR", "$ndkBin/llvm-ar")
        environment("SYSROOT", ndkSysroot)
        environment(linkerVar, clang)
        environment("HOST_CC", "/usr/bin/clang")
        // Reproducible builds: strip the build machine's absolute paths (cargo
        // registry + local crate dir) from the binary. Rust otherwise embeds
        // $HOME-specific paths in panic/debug strings, so F-Droid's /home/vagrant
        // and CI's /home/runner would produce different .so bytes. Each host remaps
        // its own paths to the same constants, yielding identical output.
        val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
        val rustSrc = file("src/main/rust").absolutePath
        environment(
            "RUSTFLAGS",
            "--remap-path-prefix=$cargoHome=/cargo --remap-path-prefix=$rustSrc=/camera",
        )

        commandLine("$cargoBin/cargo", "build", "--release", "--target", triple)

        doLast {
            destSo.parentFile.mkdirs()
            soOut.copyTo(destSo, overwrite = true)
        }
    }
}

val cargoNdkBuild = tasks.register("cargoNdkBuild") {
    description = "Builds libcamera_stitch.so for all Android ABIs."
    dependsOn(perAbiBuildTasks)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
