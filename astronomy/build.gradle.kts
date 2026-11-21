plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:astronomy:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "star"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.astronomy"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native celestial transforms + sky projection (Rust). See astronomy/src/main/rust/.
rustNativeLib("astronomy_engine", "astronomy")

// Only the classes below actually load a native library: they call
// CoordinateTransforms.batchRaDecToAltAz or projectAll (both read
// AstronomyNative.available — NativeLoader.load("astronomy_engine") —
// and forward to the Rust batch entry points when present),
// ProjectionEdgeCasesTest also calls AstronomyNative directly, and the
// two sky-canvas tests compose SkyCanvas over a populated sky, which
// projectAlls its stars. Quarantine those loaders into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.astronomy.domain.engine.CoordinateTransformsTest",
            "com.vayunmathur.astronomy.domain.engine.EngineEdgeCasesTest",
            "com.vayunmathur.astronomy.domain.projection.ProjectionEdgeCasesTest",
            "com.vayunmathur.astronomy.ui.AstronomyScreensTest",
            "com.vayunmathur.astronomy.ui.SkyCanvasDrawTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // every class below instantiates AstronomyViewModel (MainActivityRoboTest
    // via MainActivity's by viewModels()), whose viewModelScope owns
    // AstronomyPrefs over the process-wide DataStoreUtils singleton
    // ("datastore_default"), eagerly stateIns the visible-sky flow, and
    // launches orientation/location/ticker collectors that outlive a
    // single test; the VM also recomputes the sky through the native
    // batch path above. Persisted prefs and leaked collectors leaking
    // into a batched neighbour are the appstore DataStore hang shape.
    // All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.astronomy.MainActivityRoboTest",
            "com.vayunmathur.astronomy.NavigationRouteTest",
            "com.vayunmathur.astronomy.platform.AstronomyViewModelLocationTest",
            "com.vayunmathur.astronomy.platform.AstronomyViewModelTest",
            "com.vayunmathur.astronomy.ui.AstronomyPagesTest",
            "com.vayunmathur.astronomy.ui.ObjectDetailPagesTest",
            "com.vayunmathur.astronomy.ui.SkyMapScreenTest",
        )
    )
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
}
