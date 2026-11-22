plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:translate:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "translate"
}

// This module has no rustNativeLib of its own: the native load arrives
// transitively from :library:ml (libmodelrunner, via :library:ocr too),
// which is what puts this module's tests on the forkEvery = 1 path at
// all. Only the classes below actually load it: PlatformEnginesTest
// drives NllbTranslator/MadladTranslator availability (and fabricates
// model files in-class, so a handle construction — which reads
// MlNative.isAvailable — is reachable), and ImageTranslateScreenTest
// reaches TextRecognizer through the OCR seam (its init reads
// MlNative.isAvailable). Tests that merely reference a handle's pure
// constants, stub the engines, or compose screens from fake actions
// never load the library and stay in the main task. Quarantine the
// loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.translate.platform.PlatformEnginesTest",
            "com.vayunmathur.translate.ui.ImageTranslateScreenTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // TranslateViewModel launches a viewModelScope init read over
    // TranslateSettings (the module's preferences DataStore) and calls
    // translator.isAvailable() from it, stateIns the process-wide
    // TranslateRepository.get singleton's Room flows in viewModelScope,
    // and is never cleared when the test never calls onCleared; the
    // repository singleton and persisted DataStore/Room state also leak
    // into classes expecting defaults. Audit rule: any test
    // instantiating TranslateViewModel (or launching MainActivity, which
    // builds one), using TranslateSettings directly, or using the
    // TranslateRepository singleton belongs here. ImageTranslateScreenTest
    // also instantiates the ViewModel but is already quarantined as a
    // native loader above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.translate.data.DataLayerTest",
            "com.vayunmathur.translate.data.DbBackedRepositoryTest",
            "com.vayunmathur.translate.platform.SharedImageIntentTest",
            "com.vayunmathur.translate.platform.ViewModelAndPagesTest",
            "com.vayunmathur.translate.ui.CameraScreenProbeTest",
            "com.vayunmathur.translate.ui.DbBackedPagesTest",
            "com.vayunmathur.translate.ui.ExpandedAndLoopTest",
            "com.vayunmathur.translate.ui.ImageAndActivityTest",
        )
    )
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.translate"
    }
}

dependencies {
    // On-device translation runs on `:library:ml`'s own Vulkan compute runtime (NLLB-200 via
    // `NllbHandle`); the two model files are downloaded at runtime from the mirror.
    implementation(project(":library:ml"))
    implementation(project(":library:downloadservice"))
    implementation(project(":library:ocr"))
    implementation(project(":library:network"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.compose)
    implementRoom(libs)
    implementation(project(":library:room"))
}
