plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:euicc:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "sim_card"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.euicc"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// SGP.22 core (ASN.1 + ES9+/ES10 + crypto) is native, loaded as libeuicc.so.
// See euicc/src/main/rust/.
rustNativeLib("euicc")

// Only the classes below actually load libeuicc: they call EuiccNative
// externs directly, or open the ISD-R channel over the fake telephony
// shadow so the native core runs (any touch of EuiccNative, including
// installing activeChannel, initialises the object and loads the
// library). Tests that never open a channel — the no-hardware
// EuiccManagerService/EuiccChannelManager paths throw before
// EuiccNative is touched — and pure data/UI tests stay in the main
// task. Quarantine the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.euicc.EuiccNativeTest",
            "com.vayunmathur.euicc.FakeEuiccIntegrationTest",
            "com.vayunmathur.euicc.FakeSmdpIntegrationTest",
            "com.vayunmathur.euicc.telephony.EuiccChannelManagerFakeTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion
    // task: EuiccViewModel's init calls the process-wide
    // NetworkClient.init and launches a viewModelScope reload whose IO
    // work is never cleared when the test never calls onCleared; its
    // tests drain state by polling the main looper. Audit rule: any
    // test instantiating EuiccViewModel (or rendering Navigation,
    // which takes one) belongs here. This module's DataStore
    // dependency is unused by main/test sources, so no DataStore
    // polluters exist here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.euicc.NavigationAndActivitiesTest",
            "com.vayunmathur.euicc.platform.EuiccViewModelTest",
        )
    )
}

dependencies {
    // Compile-only stubs for the framework's @SystemApi eSIM LPA classes
    // (android.service.euicc.*, EuiccProfileInfo). Provided by the framework at
    // runtime on a system image; must NOT be packaged.
    compileOnly(project(":library:aosp-stubs"))
    // Unit tests reference the EuiccService surface at compile time; at runtime
    // Robolectric supplies the framework's real classes.
    testCompileOnly(project(":library:aosp-stubs"))
    // Rust reaches the SM-DP+ over HTTP through the flat-framed JNI bridge in
    // library:network (NativeHttpBridge), so libeuicc.so links no TLS of its own.
    implementation(project(":library:network"))
    // Local per-profile nicknames / prefs.
    implementation(libs.androidx.datastore.preferences)
    // QR activation-code scanning (CameraX preview + ZXing decode).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
