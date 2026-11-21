plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:kart:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "directions_car"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.kart"
    }
}

// ONN 4K box (32-bit armeabi-v7a userspace, Mali-G31, no Vulkan): the GLES
// fallback in src/main/rust/gles renders 3D there. Same arrangement as
// :cast:tv + :library:e2ee-p2p (the only armv7 precedent in the tree).
nativeAbis {
    armv7 = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native 3D racer engine (Sunset Oval track, kart physics, chase cam, items +
// AI). Same arrangement as games/voxels: the crate builds into rustJniLibs and
// KartNative.isAvailable gates every call, so devices without the .so run the
// placeholder race. Listing images are hand-captured (Layoutlib can't render
// the Vulkan surface); see the plan's verification notes.
// armeabi-v7a as well as arm64: the ONN box runs this app directly on 32-bit
// Google TV hardware. Only kart_engine opts in (:library:e2ee-p2p precedent) —
// no other native module changes.
rustNativeLib(
    "kart_engine",
    "kart",
    extraAbis = listOf(ABI_ARMV7),
    // kart_gles.* are GLSL ES, compiled at run time by the GLES fallback.
    shaders = listOf("kart.vert", "kart.frag"),
)

// Only the classes below actually load a native library: KartHostAndNativeTest
// calls KartNative.* directly, KartSurfaceViewTest drives KartSurfaceView's
// surface callbacks (each reads KartNative.isAvailable), and the two screen
// tests compose KartGameScreen's RacingBody, which reads
// KartNative.isAvailable before composing KartSurfaceView. Touching
// KartNative triggers its clinit (NativeLoader.load("kart_engine")).
// Quarantine those loaders into testDevUnitTestNative (forkEvery = 1); the
// remaining classes batch 20-per-JVM in testDevUnitTest, which depends on
// the quarantine task so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.games.kart.platform.KartHostAndNativeTest",
            "com.vayunmathur.games.kart.ui.KartGameScreenTest",
            "com.vayunmathur.games.kart.ui.KartRaceSurfaceTest",
            "com.vayunmathur.games.kart.ui.KartSurfaceViewTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // KartViewModelTest and MainActivityTest instantiate KartViewModel,
    // whose viewModelScope collects the process-wide preferencesDataStore
    // ("settings" via KartDataStore, plus "kart_identity" via
    // KartPairingStore) for the VM's lifetime and whose races bank cup /
    // win / best-lap state other classes expect at defaults;
    // KartViewModelTest also mutates the global KartNative.isAvailable.
    // KartStoresTest writes the same two DataStores directly and asserts
    // defaults first. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.games.kart.MainActivityTest",
            "com.vayunmathur.games.kart.data.KartStoresTest",
            "com.vayunmathur.games.kart.platform.KartViewModelTest",
        )
    )
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":sdk:controller"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.androidx.datastore.preferences)
}
