plugins {
    id("common-conventions-library")
}

android {
    // The FD-based handles (Whisper/Maia/Clip/Nnfp, Supertonic) can only open
    // assets that AAPT stored uncompressed - their create() calls openFd.
    // Declaring it here makes the library's own (test) APK honour that, which
    // is also what a consuming app must do for these models to load at all.
    androidResources {
        noCompress += listOf("maml", "bin")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// The Vulkan compute ML runtime: `library/ml/src/main/rust`, on ash.
//
// No `externalNativeBuild` and no `ndkVersion`: there is no C++ or CMake in this repo
// (CMake was deliberately removed — see maps/build.gradle.kts:29-31), so cargo does the
// cross-compile and the NDK is only reached for its clang wrappers and glslc.
//
// Shaders are GLSL compute compiled to SPIR-V by the `compileShaders` task (build-logic
// GlslcCompile); `*.glsl` are #include-only and not compiled on their own.
// `-PemulatorAbi=x86_64` additionally cross-compiles for the emulator's ABI so the gate can be
// exercised on an x86_64 host. Off unless asked for, so no default or release build is affected.
rustNativeLib(
    "modelrunner",
    "ml",
    extraAbis = if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
        listOf(ABI_X86_64)
    } else {
        emptyList()
    },
    shaders = listOf("*.comp"),
    glslcArgs = listOf("--target-env=vulkan1.1", "-O"),
)
