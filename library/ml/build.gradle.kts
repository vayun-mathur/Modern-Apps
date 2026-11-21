plugins {
    id("common-conventions-library")
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

// Only the classes below actually load a native library: they construct a
// handle, segmenter or recogniser (WhisperHandle, ClipHandle,
// Gemma4AudioHandle/Gemma4VisionHandle, TextRecognizer, FaceDetector, the
// segmenters, SupertonicSynthesizer), whose init reads MlNative.isAvailable
// — the one System.loadLibrary("modelrunner") attempt — or read that flag
// directly (HandlesUnavailableTest's `native availability flag` test). The
// pure Gemma companion tests (render/declare/parse, fitAudio/promptCeiling,
// markers, n-gram draft, sampling, tool calls) never construct a handle and
// never touch MlNative, so they stay in the main task. Quarantine the
// loaders into testDebugUnitTestNative (forkEvery = 1); the remaining
// classes batch 20-per-JVM in testDebugUnitTest, which depends on the
// quarantine task so the usual invocation still runs everything. No
// isolatedClasses: this module has no DataStore/ViewModel or other
// process-wide singleton for a test to pollute.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.library.ml.DataSemanticsTest",
            "com.vayunmathur.library.ml.HandlesUnavailableTest",
            "com.vayunmathur.library.ml.MlDeepTest",
            "com.vayunmathur.library.ml.SegmentationAndFacesTest",
            "com.vayunmathur.library.ml.SupertonicTest",
        )
    )
}
