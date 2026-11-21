plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "location_on"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.maps"
    }
    // PoiIndexTest exercises the real side-file reader, which logs. Without this every
    // android.util.Log call throws "not mocked" and the test can only cover code that never logs.
    testOptions.unitTests.isReturnDefaultValues = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native offline routing engine + traffic MVT tile encoder (Rust). See
// maps/src/main/rust/. Replaces the previous CMake/C++ libofflinerouter.
rustNativeLib("offlinerouter", "maps")

// Only the classes below actually load a Rust cdylib: they execute a path
// that touches OfflineRouterNative.available (libofflinerouter, via
// OfflineRouter.getRoute / OfflineRouterTransit / OfflineRouterTraffic's
// tile server / road-route planning) or render MapSurface (libmap_renderer
// via library:map's MapNative). Tests that merely construct
// OfflineRouter.Raw* rows or call the pure decoding helpers never load
// either library and stay in the main task. Quarantine the loaders and
// the shared-JVM polluters below into testDevUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDevUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.maps.ui.PlaceHeaderDeepSlice3ExtraTest",
            "com.vayunmathur.maps.ui.map.Slice1Pass2MapSurfaceProbeTest",
            "com.vayunmathur.maps.util.MapsTrafficServerC5Test",
            "com.vayunmathur.maps.util.MapsTransitC5Test",
            "com.vayunmathur.maps.util.MapsTransitLinesC5Test",
            "com.vayunmathur.maps.util.OfflineRouterSlice3ExtraTest",
            "com.vayunmathur.maps.util.OfflineRouterTransitSlice3ExtraTest",
            "com.vayunmathur.maps.util.RoadRoutesSlice3Test",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // DataStoreUtils is a process-wide singleton whose init eagerly
    // collects the preferences DataStore, and the DataStore-backed
    // ViewModels (MapSettings / MapsSearch / Parking / SavedPlaces /
    // SelectedFeature — plus TransitStopsViewModel's departures flow)
    // turn its flows into viewModelScope collectors
    // (several SharingStarted.Eagerly) that are never cleared when the
    // test never calls onCleared; persisted DataStore state also leaks
    // into classes expecting defaults. Same hazard for tests that drive
    // the NavigationSessionManager / PoiIndex process-wide singletons.
    // Audit rule: any test instantiating one of those ViewModels (or
    // launching MainActivity, which builds them), using
    // DataStoreUtils.getInstance / a DataStore-backed store directly, or
    // calling NavigationSessionManager.init/start belongs here. All
    // other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.maps.MainActivityNavigationSlice3Test",
            "com.vayunmathur.maps.MainActivitySlice3Test",
            "com.vayunmathur.maps.MapsActivityW6Test",
            "com.vayunmathur.maps.car.CarSlice3ExtraTest",
            "com.vayunmathur.maps.car.MapsCarNavScreenC5Test",
            "com.vayunmathur.maps.data.StoresSlice3Test",
            "com.vayunmathur.maps.ui.BottomSheetClicksSlice2Test",
            "com.vayunmathur.maps.ui.BottomSheetContentVmSlice2Test",
            "com.vayunmathur.maps.ui.BottomSheetHeaderDirectionsSlice2Test",
            "com.vayunmathur.maps.ui.BottomSheetWiringSlice2Test",
            "com.vayunmathur.maps.ui.ComposeSurfacesSlice3ExtraTest",
            "com.vayunmathur.maps.ui.GoogleConsentDialogSlice2Test",
            "com.vayunmathur.maps.ui.GoogleConsentRecordSlice2Test",
            "com.vayunmathur.maps.ui.MapSheetEffectsSlice3ExtraTest",
            "com.vayunmathur.maps.ui.MapsComposeC5bTest",
            "com.vayunmathur.maps.ui.MapsContentBoxC5Test",
            "com.vayunmathur.maps.ui.MapsPickingC5Test",
            "com.vayunmathur.maps.ui.SavedPlacesDialogsSlice3ExtraTest",
            "com.vayunmathur.maps.ui.SavedPlacesPageSlice3Test",
            "com.vayunmathur.maps.ui.SearchActionsSlice3ExtraTest",
            "com.vayunmathur.maps.ui.Slice3FinalBatchTest",
            "com.vayunmathur.maps.ui.Slice3Pass5SheetsTest",
            "com.vayunmathur.maps.ui.WideHostSlice3ExtraTest",
            "com.vayunmathur.maps.ui.WideLayoutSlice3Test",
            "com.vayunmathur.maps.ui.map.Slice1InitialCameraComposeTest",
            "com.vayunmathur.maps.ui.map.Slice1Pass2InitialCameraTest",
            "com.vayunmathur.maps.ui.settings.Slice1Pass2SettingsTest",
            "com.vayunmathur.maps.ui.settings.Slice1SettingsComposeTest",
            "com.vayunmathur.maps.util.MapsNavSensorC5Test",
            "com.vayunmathur.maps.util.MapsSearchVmC5Test",
            "com.vayunmathur.maps.util.MapsSelectedFeatureC5Test",
            "com.vayunmathur.maps.util.NavigationServiceModesSlice3ExtraTest",
            "com.vayunmathur.maps.util.NavigationServiceSlice3ExtraTest",
            "com.vayunmathur.maps.util.NavigationSessionSlice3ExtraTest",
            "com.vayunmathur.maps.util.OverlayDefaultsSlice3Test",
            "com.vayunmathur.maps.util.PoiMapViewModelSlice2Test",
            "com.vayunmathur.maps.util.PoiViewportSlice2Test",
            "com.vayunmathur.maps.util.RecalcAndSearchSlice3ExtraTest",
            "com.vayunmathur.maps.util.SelectedFeatureSlice3ExtraTest",
            "com.vayunmathur.maps.util.SelectedPoiInfoSlice3ExtraTest",
            "com.vayunmathur.maps.util.SelectedRoutesReviewsSlice3ExtraTest",
            "com.vayunmathur.maps.util.Slice3Pass3NavTest",
            "com.vayunmathur.maps.util.Slice3Pass4TransitVmTest",
            "com.vayunmathur.maps.util.TransitAndRideSlice3ExtraTest",
            "com.vayunmathur.maps.util.UtilBatch2Slice3Test",
            "com.vayunmathur.maps.util.ViewModelsSlice3Test",
        )
    )
}

dependencies {
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(project(":library:network"))

    implementation(project(":library:downloadservice"))

    // WorkManager: the map-overlay check worker outlives the settings screen.
    implementation(project(":library:work"))

    // Manifest JSON parsing (pure JVM: unit-testable, unlike org.json).
    implementation(libs.kotlinx.serialization.json)

    // Android Auto (P12): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model; `app-projected` provides the
    // phone-projected (Android Auto) host connection. Only pulled into the car
    // code path — the phone UI is untouched.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    // Host-side car screens (CarSearchScreen/CarMapRenderer) run against
    // TestCarContext under Robolectric; the real head unit is DHU-only.
    testImplementation(libs.androidx.car.app.testing)
    testImplementation(libs.androidx.work.testing)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
