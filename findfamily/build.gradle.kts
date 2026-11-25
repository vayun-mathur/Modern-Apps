plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "moved_location"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.findfamily"
    }
}

// Only the classes below actually load a native library: they execute a
// path that touches the Rust pairing/PQC crypto in :library:e2ee-p2p
// (libe2ee_pqc via Pqc / PqcIdentity, reached through Networking's
// generatePqc* / seal / sign / verify helpers, TrackerProtocol.sealReport,
// CreateLinkIntent's link keygen, or TrackerBinder's tracker keygen), or
// they call Networking.init / initDirectBoot, whose PqcIdentity.loadOrCreate
// mints the identity through the same JNI bridge on a fresh store. Tests
// that merely call the pure SecurityCode / InviteFingerprint helpers, use
// fake keypair mints (PoweredOffRecoveryTest), or hit the fail-closed
// no-session branches never load the library and stay in the main task.
// Quarantine the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.findfamily.Y3FinalReportTest",
            "com.vayunmathur.findfamily.intents.AssistantIntentsRealRepoTest",
            "com.vayunmathur.findfamily.platform.FixCeilingAlarmTest",
            "com.vayunmathur.findfamily.platform.PoweredOffBeaconArmTest",
            "com.vayunmathur.findfamily.platform.PoweredOffBeaconRealRepoTest",
            "com.vayunmathur.findfamily.platform.UwbHandshakeSdk36Test",
            "com.vayunmathur.findfamily.tracker.BleFailClosedTest",
            "com.vayunmathur.findfamily.ui.MainPageChromeRealRepoTest",
            "com.vayunmathur.findfamily.ui.dialogs.DialogsRealRepoInteractionsTest",
            "com.vayunmathur.findfamily.util.CrowdFindingRealRepoTest",
            "com.vayunmathur.findfamily.util.FindFamilyViewModelStatesRealTest",
            "com.vayunmathur.findfamily.util.InboundEnrichRealRepoTest",
            "com.vayunmathur.findfamily.util.LocationTrackingHeartbeatTest",
            "com.vayunmathur.findfamily.util.NetworkingCodecTest",
            "com.vayunmathur.findfamily.util.NetworkingDirectBootBootstrapTest",
            "com.vayunmathur.findfamily.util.NetworkingDirectBootTest",
            "com.vayunmathur.findfamily.util.NetworkingFramesRealRepoTest",
            "com.vayunmathur.findfamily.util.NetworkingLiveSessionTest",
            "com.vayunmathur.findfamily.util.NetworkingOfflineTest",
            "com.vayunmathur.findfamily.util.NetworkingOutboundSpoolTest",
            "com.vayunmathur.findfamily.util.NetworkingUwbAndBackupTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // DataStoreUtils is a process-wide singleton whose init eagerly
    // collects the preferences DataStore, and FindFamilyViewModel turns
    // its (and the repository's) flows into viewModelScope collectors
    // (several SharingStarted.WhileSubscribed / Eagerly) that are never
    // cleared when the test never calls onCleared; persisted DataStore
    // state also leaks into classes expecting defaults. Same hazard for
    // tests that drive the UwbSessionManager process-wide StateFlow
    // singleton. Audit rule: any test instantiating FindFamilyViewModel
    // (or launching MainActivity, which builds one), using
    // DataStoreUtils.getInstance / a TrackerStore over it directly, or
    // driving UwbSessionManager.init/start belongs here. The same rule
    // covers indirect DataStore drivers: LocationServiceController's
    // persisted flags are DataStoreUtils-backed, and its flow collectors
    // leaked into a sibling class (UncaughtExceptionsBeforeTest) and its
    // persisted flags broke the defaults-first assertions when
    // PlatformServicesTest and MapAndControllerTest shared a JVM.
    // The LocationTrackingService lifecycle drivers (Y3TrackingService /
    // TrackingServiceLifecycle / LocationTrackingLifecycleReal) also
    // belong here: startTracking/onStartCommand launch the service's
    // scope loops, which outlive the sandbox that created them. Same
    // evidence for LocationTrackingInboundTest: batched, its
    // processIncomingLocations Dispatchers.IO enrich job outlived the
    // test's closed in-memory Room database and surfaced as
    // UncaughtExceptionsBeforeTest ("Database is closed" at
    // FindFamilyRepository.updateLocationMeta) in whichever batched
    // runTest class ran next in the JVM. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.findfamily.MainActivityRealLaunchTest",
            "com.vayunmathur.findfamily.Y3TrackingServiceTest",
            "com.vayunmathur.findfamily.platform.PlatformServicesTest",
            "com.vayunmathur.findfamily.platform.UwbSessionManagerSdk36Test",
            "com.vayunmathur.findfamily.service.TilesBootAndChannelsTest",
            "com.vayunmathur.findfamily.ui.DialogsWithVmTest",
            "com.vayunmathur.findfamily.ui.FirstRunSheetTest",
            "com.vayunmathur.findfamily.ui.MainPageActionsRealRepoTest",
            "com.vayunmathur.findfamily.ui.MainPageNavigationRealRepoTest",
            "com.vayunmathur.findfamily.util.CrowdFindingAndControllerTest",
            "com.vayunmathur.findfamily.util.DirectBootAndStoresRealTest",
            "com.vayunmathur.findfamily.util.FindFamilyViewModelDbTest",
            "com.vayunmathur.findfamily.util.FindFamilyViewModelProbeTest",
            "com.vayunmathur.findfamily.util.FindFamilyViewModelRealRepoTest",
            "com.vayunmathur.findfamily.util.LocationTrackingInboundTest",
            "com.vayunmathur.findfamily.util.LocationTrackingLifecycleRealTest",
            "com.vayunmathur.findfamily.util.MapAndControllerTest",
            "com.vayunmathur.findfamily.util.PlatformAndUwbStateTest",
            "com.vayunmathur.findfamily.util.TrackingServiceLifecycleTest",
            "com.vayunmathur.findfamily.util.UwbSessionManagerRealRepoTest",
        )
    )
}

dependencies {

    // HTTP + JSON
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))
    testImplementation(libs.androidx.work.testing)

    implementation(project(":library:image"))

    implementation(project(":library:map"))
    implementation(project(":library:wearlink"))
    // @SystemApi android.nearby.NearbyManager, for powered-off finding. Present at runtime,
    // absent from the public SDK, and must NOT be packaged. See :library:aosp-stubs.
    compileOnly(project(":library:aosp-stubs"))
}

// The Compose screenshot renderer draws previews from the compiled `screenshotTest` classes
// directory (the renderer's screenshotProjectClassPath). JVM resources placed under
// src/screenshotTest/resources are NOT otherwise put on that classpath, so the metadata
// previews can't load the bundled CARTO map via getResourceAsStream. Stage those resources
// into the classes output so the previews can read them at render time. Because this asset
// lives only in the screenshotTest source set it ships in neither the dev nor the release APK.
val screenshotTestClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/devScreenshotTest/compileDevScreenshotTestKotlin/classes"
)
val stageDevScreenshotTestResources = tasks.register<Copy>("stageDevScreenshotTestResources") {
    from(layout.projectDirectory.dir("src/screenshotTest/resources"))
    into(screenshotTestClasses)
    mustRunAfter("compileDevScreenshotTestKotlin")
    outputs.upToDateWhen { false }
}
// `rustNativeTests` (above) makes RustNative's split register a
// `…Native` companion for every Test task — including the screenshot
// renderer's update/validate tasks — and both the renderer and its
// companion read the compiled classes dir this staging task writes into.
// Match by prefix so both the AGP tasks and their `Native` companions
// declare the dependency; naming only the two AGP tasks leaves the
// companions with an implicit-dependency validation failure.
tasks.matching {
    it.name.startsWith("updateDevScreenshotTest") || it.name.startsWith("validateDevScreenshotTest")
}.configureEach {
    dependsOn(stageDevScreenshotTestResources)
}
