plugins {
    id("common-conventions-library")
}
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // FrameLoopWakeTest constructs a real `Application` for its `Context`: without this every
    // `android.util`/`android.app` stub throws "not mocked". Same flag `:maps` sets for
    // `PoiIndexTest`'s side-file reader, which logs.
    testOptions.unitTests.isReturnDefaultValues = true
}
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation)

    // For androidx.lifecycle.compose.LocalLifecycleOwner, which drives the frame loop's
    // ON_START/ON_STOP reconciliation. Scoped to this module rather than the convention
    // plugin: one consumer does not justify reconfiguring every module.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The renderer's HTTP transport reaches the app's NetworkClient through JNI:
    // the Rust core calls back through library/jni-http into NativeHttpBridge
    // (com.vayunmathur.library.network), which the HOST app provides — all ten
    // map hosts already depend on :library:network directly. The bridge class is
    // resolved at runtime from the app's classpath, so this module needs no
    // :library:network edge (and gains no ACCESS_NETWORK_STATE from it); the
    // tile-range requests keep library:network's reduced CA bundle and
    // HttpURLConnection-only policy with no second TLS stack (#884).
    //
    // Deliberately not :library — that would drag Room, DataStore, Navigation 3 and
    // :sdk:games onto this module's compile classpath for one object.

    // Own GeoPoint/GeoBounds – this module has no third-party geometry dependency.

    // :library:image is deliberately gone: it existed to fetch and decode CARTO raster
    // PNGs, and there is no raster tile path any more.

    // The on-device screenshot harness (`src/androidTest`), which is the only way to see what
    // the renderer draws: Vulkan needs a real GPU, so the host probes can only measure the CPU
    // pipeline. The coordinates live in the version catalog with the rest of the repo's
    // dependencies; every app module also gets the shared launch-no-crash androidTest
    // dependencies from `common-conventions-app`.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.compose.foundation)
    // Supplies the bare `ComponentActivity` the harness hosts the map in.
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// The Vulkan vector-tile renderer: `library/map/src/main/rust`, on ash.
//
// arm64 only, which is what the seven consumer apps build
// (`common-conventions-app.gradle.kts:131-133`). `libvulkan.so` is on the device, so this
// .so is our own code and nothing else — the whole size argument against bundling Dawn.
//
// `-PemulatorAbi=x86_64` additionally builds x86_64 so the renderer can be run in an
// emulator on an x86_64 host. Vulkan cannot be verified on a build host, and an arm64
// system image on such a host means full-system QEMU translation, so this is the only
// practical way to exercise the GPU path here. Never on by default.
//
// Shaders are GLSL compiled to SPIR-V by the `compileShaders` task (build-logic
// GlslcCompile), which fails the build if glslc is missing or a shader does not compile.
rustNativeLib(
    "map_renderer",
    "map",
    extraAbis = if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
        listOf(ABI_X86_64)
    } else {
        emptyList()
    },
    shaders = listOf("*.vert", "*.frag"),
    glslcArgs = listOf("--target-env=vulkan1.1", "-O"),
)

// Only the classes below actually load a native library: they compose
// VectorMap, which binds a TextureView surface through VulkanMapSurface's
// MapSurfaceHost into SurfaceMapRenderer.attachSurface — the path that
// reads MapNative.isAvailable and queues MapNative.create for
// libmap_renderer. Every other class is camera/projection/gesture math,
// value types, or a SurfaceMapRenderer with no surface attached (whose
// mutators are dropped by MapNativeLink before any JNI: FrameLoopWakeTest,
// MapRenderThreadTest and RendererStateTest pin exactly that no-JNI
// contract), and MapGesturesTest exercises only the gesture modifier, not
// VectorMap. Quarantine the loaders into testDebugUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDebugUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything. No isolatedClasses: no
// DataStore/ViewModel here, and the no-surface renderers share only
// MapRenderThread's queue, which they never post to while unattached.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.library.map.MapAttachTest",
            "com.vayunmathur.library.map.MapComposeTest",
        )
    )
}
