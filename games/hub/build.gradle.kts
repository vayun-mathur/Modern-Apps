plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:hub:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "stadia_controller"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.hub"
    }
}

// This module has no rustNativeLib of its own: the native load arrives
// transitively from :library:avatar (libyuu_avatar via YuuAvatarNative) and
// :library:e2ee-p2p, which is what puts this module's tests on the
// forkEvery = 1 path at all. Only the classes below actually load a native
// library: they compose YuuEditorScreen, whose preview composes
// YuuAvatar3D — the component that reads YuuAvatarNative.isAvailable
// (NativeLoader.load("yuu_avatar")) before any surface work. Quarantine
// those loaders into testDevUnitTestNative (forkEvery = 1); the remaining
// classes batch 20-per-JVM in testDevUnitTest, which depends on the
// quarantine task so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.games.hub.ui.HubCoveragePassTest",
            "com.vayunmathur.games.hub.ui.HubYuuEditorCoverageTest",
            "com.vayunmathur.games.hub.ui.HubYuuEditorPass3Test",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // every class below but PlayOnTvSessionTest builds GameHubViewModel
    // and/or GamesHubRepository.get — the process-wide repository
    // singleton whose Room db is cached on first access, fed by each
    // class's own static sharedDb swapped into the process-wide
    // `databases` map; the VM's viewModelScope stateIn flows collect the
    // repository for its lifetime. HubPagesCoverageTest and
    // HubScreensPass3Test also compose Yuu screens (native, above), but
    // their repository/VM pollution is the batch-breaking hazard.
    // PlayOnTvSessionTest persists its sender id in the process-wide
    // "hub_controller" preferencesDataStore and expects the second
    // session to reuse it. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.games.hub.data.HubStoresAndViewModelTest",
            "com.vayunmathur.games.hub.network.PlayOnTvSessionTest",
            "com.vayunmathur.games.hub.provider.HubConvertersMoreTest",
            "com.vayunmathur.games.hub.provider.HubProviderTest",
            "com.vayunmathur.games.hub.ui.HubNavigationCoverageTest",
            "com.vayunmathur.games.hub.ui.HubPagesCoverageTest",
            "com.vayunmathur.games.hub.ui.HubScreensPass3Test",
        )
    )
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:format"))
    implementation(project(":library:ui"))
    implementation(project(":library:room"))
    implementation(project(":library:avatar"))
    implementation(project(":sdk:games"))
    // "Play on TV": the generic phone-as-controller SDK (pad UI + discovery +
    // client) plus PQC sealing for the handshake's session secret.
    implementation(project(":sdk:controller"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.androidx.datastore.preferences)
    implementRoom(libs)
}
