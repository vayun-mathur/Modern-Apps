plugins {
    id("common-conventions-app")
    // No metadata convention. The viewfinder is the screen, and Layoutlib cannot open a
    // camera, so there is nothing a Compose preview could capture — with no SurfaceRequest
    // the preview falls back to a black Box. The listing images are hand-captured and
    // committed under metadata_data/photos/camera/phone/, which the release build
    // picks up into graphics/phone-screenshots.
}

launcherIcon {
    symbol = "photo_camera"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.camera"
    }
    androidResources {
        // *.maml are read straight out of the APK by the segmenters, so
        // leave them uncompressed: fp16 weights barely deflate, and a compressed asset would
        // have to be inflated into a heap buffer before it could be uploaded.
        noCompress += "maml"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native panorama stitcher + night burst aligner (Rust). See camera/src/main/rust/.
// camera_stitch also links vision_core (library/vision), and :library:ml's
// modelrunner is in the graph too, so the host .so set is not just camera's own.
rustNativeLib("camera_stitch", "camera")

// Only the classes below actually load a native library: they call
// StitchNative directly (the two host-.so tests), execute a path that
// touches StitchNative.isAvailable (PanoramaEngine.startSweep/stitch,
// NightCaptureEngine.merge), or construct a segmenter that loads
// :library:ml's modelrunner (BokehAnalyzer -> SelfieSegmenter,
// StillBokehRenderer -> ModnetSegmenter). Tests that merely call the pure
// math helpers (NightCaptureEngine.mergeScaledDimensions,
// GpuStitcher.parseEstimate, LumaPlaneMath) or the pure Camera2 driver
// fakes under platform/camera2 never load either library and stay in the
// main task. Quarantine the loaders and the shared-JVM polluters below
// into testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.camera.platform.BokehAnalyzerMathTest",
            "com.vayunmathur.camera.platform.BurstConvertExtraTest",
            "com.vayunmathur.camera.platform.CameraControlsExtraTest",
            "com.vayunmathur.camera.platform.CameraCovC2LogicTest",
            "com.vayunmathur.camera.platform.CameraCovC2PanoramaSensorTest",
            "com.vayunmathur.camera.platform.CameraCovW3Wave4Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave8Test",
            "com.vayunmathur.camera.platform.CameraCovX4SessionsTest",
            "com.vayunmathur.camera.platform.LumaStatsNativeTest",
            "com.vayunmathur.camera.platform.NightCaptureEngineMathTest",
            "com.vayunmathur.camera.platform.PanoramaEngineExtraTest",
            "com.vayunmathur.camera.platform.PanoramaSaveExtraTest",
            "com.vayunmathur.camera.platform.PortraitBokehMathTest",
            "com.vayunmathur.camera.platform.StillEncoderNativeTest",
            "com.vayunmathur.camera.ui.CameraCovX4ComposeTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // CameraViewModel's init runs loadSettings() over the process-wide
    // DataStoreUtils singleton and starts viewModelScope collectors
    // (SharingStarted.Eagerly stateIn flows plus launch{} collectors)
    // that are never cleared when the test never calls onCleared;
    // persisted DataStore state also leaks into classes expecting
    // defaults. Audit rule: any test instantiating CameraViewModel or
    // launching MainActivity (which builds one), or using
    // DataStoreUtils / the CameraSettings extensions directly, belongs
    // here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.camera.MainActivityExtraTest",
            "com.vayunmathur.camera.data.SafPrepareExtraTest",
            "com.vayunmathur.camera.platform.AeConvergenceGateTest",
            "com.vayunmathur.camera.platform.CameraCovC2CapabilitiesTest",
            "com.vayunmathur.camera.platform.CameraCovC2SessionsTest",
            "com.vayunmathur.camera.platform.CameraCovW3MockSessionsTest",
            "com.vayunmathur.camera.platform.CameraCovW3SessionsTest",
            "com.vayunmathur.camera.platform.CameraCovW3Wave11Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave3Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave5Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave6Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave7Test",
            "com.vayunmathur.camera.platform.CameraCovW3Wave9Test",
            "com.vayunmathur.camera.platform.CameraCovX4CaptureTest",
            "com.vayunmathur.camera.platform.CameraCovX4CharacteristicsTest",
            "com.vayunmathur.camera.platform.CameraSessionBindRetryTest",
            "com.vayunmathur.camera.platform.CameraSessionsExtraTest",
            "com.vayunmathur.camera.platform.CameraSettingsExtraTest",
            "com.vayunmathur.camera.platform.CameraViewModelLogicTest",
            "com.vayunmathur.camera.platform.CaptureProcessingMathTest",
            "com.vayunmathur.camera.platform.CaptureRoutingExtraTest",
            "com.vayunmathur.camera.platform.ControlsRecordingExtraTest",
            "com.vayunmathur.camera.platform.FinalCoverageExtraTest",
            "com.vayunmathur.camera.platform.ManualControlsExtraTest",
            "com.vayunmathur.camera.platform.ManualPreviewExposureTest",
            "com.vayunmathur.camera.platform.MediaStoreSaverAndProcessingTest",
            "com.vayunmathur.camera.platform.MotionPhotoCaptureStateTest",
            "com.vayunmathur.camera.platform.NightCaptureRestoreTest",
            "com.vayunmathur.camera.platform.NightLogicExtraTest",
            "com.vayunmathur.camera.platform.NightSessionEntryExtraTest",
            "com.vayunmathur.camera.platform.QuerySessionConfigExtraTest",
            "com.vayunmathur.camera.platform.RecordingRebindDeferralTest",
            "com.vayunmathur.camera.platform.Restore3AExtraTest",
            "com.vayunmathur.camera.platform.SessionEntryPointsTest",
            "com.vayunmathur.camera.platform.SessionLaddersExtraTest",
            "com.vayunmathur.camera.platform.SingleCaptureNightTest",
            "com.vayunmathur.camera.platform.SloMoLogicExtraTest",
            "com.vayunmathur.camera.platform.ZslGatingTest",
            "com.vayunmathur.camera.ui.BottomControlsComposeExtraTest",
            "com.vayunmathur.camera.ui.CameraBindFailedComposeTest",
            "com.vayunmathur.camera.ui.CameraCovC2ScreenStatesTest",
            "com.vayunmathur.camera.ui.CameraCovC2SettingsTest",
            "com.vayunmathur.camera.ui.CameraCovW3ComposeTest",
            "com.vayunmathur.camera.ui.CameraCovW3Wave6ComposeTest",
            "com.vayunmathur.camera.ui.CameraScreenComposeTest",
            "com.vayunmathur.camera.ui.CameraScreenModesComposeTest",
            "com.vayunmathur.camera.ui.CameraSettingsPageComposeTest",
            "com.vayunmathur.camera.ui.PreviewGalleryComposeExtraTest",
            "com.vayunmathur.camera.ui.SettingsInteractionsComposeTest",
        )
    )
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    // On-device portrait segmentation and matting. MediaPipe Selfie Segmentation
    // (Apache-2.0) for the preview plus MODNet photographic-portrait matting
    // (Apache-2.0) for still capture, both on our own Vulkan compute runtime;
    // see camera/src/main/assets/README.md.
    //
    // ncnn is gone from the whole tree, not just this app: `:translate` was the last consumer and
    // it now runs SMaLL-100 on `:library:ml` too, so `libncnn_android.so` is in no APK here.
    implementation(project(":library:ml"))
    // Mockito is already a cataloged test dep used the same way by
    // sibling modules (e.g. :office): ContentResolver is final-method
    // heavy, so null-stream failure paths are tested with a mock.
    testImplementation(libs.mockito.core)
}
