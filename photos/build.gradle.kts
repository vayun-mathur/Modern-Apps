plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:photos:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "photo_library"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // The four .maml files are read straight out of the APK by :library:ml, which opens them
        // with `AssetManager.openFd` and streams them into the GPU. `openFd` throws outright for a
        // deflated entry, so this is required rather than an optimisation — and it costs nothing on
        // download size, since int8 and fp16 weights barely deflate.
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

// Native pixel filters (Rust). See photos/src/main/rust/.
rustNativeLib("photos_fx", "photos")

// Only the classes below actually load a native library: they execute a
// PhotosNative-backed adjustment (Blur/Stylize/Liquify via the *Data
// applyToBitmap extensions, reached directly or through LayerCompositor)
// or probe the :library:ml bridges (FaceRecognizer.modelsAvailable /
// detectAndEmbed, ClipEmbedder.embeddingSupport — both construct
// FaceDetector/FaceEmbedder/ClipHandle, whose initialisers touch
// MlNative and load libmodelrunner). Classes that merely use the pure
// FaceRecognizer/ClipEmbedder helpers (similarity, l2Normalize,
// floatsToBytes), construct identity adjustments, or stub the ML paths
// never load either library and stay in the main task. Quarantine the
// loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.photos.data.AdjustmentBitmapsRealTest",
            "com.vayunmathur.photos.data.AdjustmentPixelsEqualityTest",
            "com.vayunmathur.photos.data.PhotosSlice1DataPass3Test",
            "com.vayunmathur.photos.util.PhotosCovC2CompositorTest",
            "com.vayunmathur.photos.util.PhotosCovC2IndexingTest",
            "com.vayunmathur.photos.util.PhotosCovC2LogicTest",
            "com.vayunmathur.photos.util.PhotosSlice1MlInertTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // GalleryViewModel and SecureFolderViewModel eagerly collect the
    // process-wide DataStoreUtils singleton (its init launches a
    // DataStore collector) from viewModelScope on the Robolectric main
    // looper, and the Sync/Face workers and LiveWallpaper paths drive
    // the same singleton; leaked collectors and persisted DataStore
    // state leak into classes expecting defaults. The editor ViewModels
    // pollute the same way: PhotoEditViewModel/VideoEditViewModel launch
    // preview/composite jobs in viewModelScope that outlive the test and
    // render through LayerCompositor on a torn-down Robolectric sandbox
    // (observed: a leaked renderTextPixels NPE surfacing as
    // UncaughtExceptionsBeforeTest in the next batched class). Audit
    // rule: any test instantiating GalleryViewModel/
    // SecureFolderViewModel/PhotoEditViewModel/VideoEditViewModel,
    // launching MainActivity (which builds them), or driving
    // SyncWorker/FaceWorker/LiveWallpaper/DataStoreUtils directly
    // belongs here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.photos.MainActivityRealLaunchTest",
            "com.vayunmathur.photos.service.LiveWallpaperServiceRealTest",
            "com.vayunmathur.photos.ui.AlbumDetailAndPickerTest",
            "com.vayunmathur.photos.ui.FavoriteToggleComposeTest",
            "com.vayunmathur.photos.ui.FavoritesWallpaperMotionTest",
            "com.vayunmathur.photos.ui.GalleryActionsRealRepoTest",
            "com.vayunmathur.photos.ui.GalleryPageDeepDriveTest",
            "com.vayunmathur.photos.ui.PhotoDetailAndFilmstripTest",
            "com.vayunmathur.photos.ui.PhotosPagesDeepDriveTest",
            "com.vayunmathur.photos.ui.VaultViewerPageRealTest",
            "com.vayunmathur.photos.ui.WallpaperSetFlowTest",
            "com.vayunmathur.photos.util.GalleryViewModelRealRepoTest",
            "com.vayunmathur.photos.util.PhotosCovC2NavigationTest",
            "com.vayunmathur.photos.util.PhotosCovC2PagesTest",
            "com.vayunmathur.photos.util.PhotosCovC2VmTest",
            "com.vayunmathur.photos.util.PhotosFavoritesAlbumTest",
            "com.vayunmathur.photos.util.PhotosNavEntriesDeepTest",
            "com.vayunmathur.photos.util.PhotosNavigationDeepTest",
            "com.vayunmathur.photos.util.PhotosPagesRealRepoTest",
            "com.vayunmathur.photos.util.PhotosPrivacyFixesTest",
            "com.vayunmathur.photos.util.PhotosSlice1VaultPage2Test",
            "com.vayunmathur.photos.util.PhotosSlice1VaultUiTest",
            "com.vayunmathur.photos.util.PhotosSlice1VaultVm2Test",
            "com.vayunmathur.photos.util.PhotosSlice1VaultVm3Test",
            "com.vayunmathur.photos.util.PhotosSlice1WorkersTest",
            "com.vayunmathur.photos.util.PhotosSlice3EditPanelsTest",
            "com.vayunmathur.photos.util.PhotosSlice3ExtrasTest",
            "com.vayunmathur.photos.util.PhotosSlice3GalleryTest",
            "com.vayunmathur.photos.util.PhotosSlice3PagesTest",
            "com.vayunmathur.photos.util.PhotosSlice3VaultTest",
            "com.vayunmathur.photos.util.SecureFolderRestoreRealTest",
            "com.vayunmathur.photos.util.SyncWorkersRealRepoTest",
            "com.vayunmathur.photos.photoslice2.BottomControlsEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.CurvesAndPanelsEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.EditPageEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.EditorStateEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.LayerControllerEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.LoadedPagesEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.OverlaysEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.PhotosCovC2EditorClickTest",
            "com.vayunmathur.photos.photoslice2.PhotosCovC2EditorTest",
            "com.vayunmathur.photos.photoslice2.RoutePreviewEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.Slice2Pass2LogicTest",
            "com.vayunmathur.photos.photoslice2.Slice2Pass2PanelsTest",
            "com.vayunmathur.photos.photoslice2.SliderGestureEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.StateLayerEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.VideoEditingPass3Test",
            "com.vayunmathur.photos.photoslice2.VideoVmEditingPass3Test",
            "com.vayunmathur.photos.ui.EditorDriveAllModesTest",
            "com.vayunmathur.photos.ui.EditorStateFunctionsTest",
            "com.vayunmathur.photos.ui.MaskAndVideoEditPageTest",
            "com.vayunmathur.photos.ui.VideoPreviewAndCropTest",
            "com.vayunmathur.photos.util.EditControllersDeepTest",
            "com.vayunmathur.photos.util.EditSaverAndVideoWriteTest",
            "com.vayunmathur.photos.util.PhotoEditDecodeAndExportTest",
            "com.vayunmathur.photos.util.PhotosCovC2PixelTest",
            "com.vayunmathur.photos.util.VideoEditAndWallpaperRealTest",
        )
    )
}

dependencies {
    implementation(libs.androidx.compose.foundation)
    // collectAsStateWithLifecycle in Navigation (as in :auto).
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(libs.androidx.exifinterface)
    // No ncnn and no onnxruntime anywhere in this APK. Face detection (SCRFD 500M), face
    // embedding (MobileFaceNet), subject segmentation (U²-Net portable), PP-OCRv5 text
    // recognition and TinyCLIP semantic search all run on :library:ml, our own Vulkan compute
    // runtime.
    implementation(project(":library:ml"))

    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)


    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))
    implementation(project(":library:ocr"))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.mockito.core)
}
