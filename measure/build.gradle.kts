plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:measure:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "straighten"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.measure"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Visual-inertial tracking runs on the in-tree Rust CV core (`measure_cv`:
// Shi-Tomasi corners, pyramidal Lucas-Kanade, essential-matrix pose) behind
// the ArHitProvider seam. No third-party native CV library ships in the APK,
// so there is no prebuilt .so to fail 16 KB page-size alignment. See
// measure/src/main/rust/.
rustNativeLib("measure_cv", "measure")

// Only the classes below actually load libmeasure_cv: they call
// MeasureCvNative directly, construct a VioEngine (whose init reads
// VioEngine.available -> MeasureCvNative.available and creates a native
// session when present), or launch MainActivity (which builds the
// ViewModel over the same engine seam). Tests that merely call the pure
// domain math, or compose contents from fake action objects without an
// engine/ViewModel, never load the library and stay in the main task.
// Quarantine the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.measure.MainActivityAndAnalyzerTest",
            "com.vayunmathur.measure.platform.DirectByteBufferVioTest",
            "com.vayunmathur.measure.platform.SyntheticVioHarnessTest",
            "com.vayunmathur.measure.ui.MeasureContentsCoverageTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // MeasureViewModel reads the process-wide DataStoreUtils singleton
    // synchronously at init and starts viewModelScope collectors on the
    // orientation/tilt sensor flows that are never cleared when the test
    // never clears the ViewModel; persisted DataStore state also leaks
    // into classes expecting defaults. (Its init also touches
    // VioEngine.available, so these classes would load the native
    // library too — another reason they cannot batch.) Audit rule: any
    // test instantiating MeasureViewModel belongs here.
    // MainActivityAndAnalyzerTest also builds one but is already
    // quarantined as a native loader above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.measure.platform.ArFlowViewModelTest",
            "com.vayunmathur.measure.platform.MeasureViewModelTest",
            "com.vayunmathur.measure.ui.ArMeasurePageTest",
            "com.vayunmathur.measure.ui.MeasurePagesTest",
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
