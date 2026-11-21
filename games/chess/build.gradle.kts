plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:chess:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        // Maia3's weights are read in place through an `AssetFileDescriptor`, and
        // `AssetManager.openFd` throws for a deflated entry. Costs nothing on download size:
        // fp16 weights barely compress.
        noCompress += "maml"
    }
}

// This module has no Rust of its own, but :library:ml registers
// libmodelrunner (rustNativeLib), so every unit test here has kept
// forkEvery = 1. Only the classes below actually load that library:
// they construct ChessViewModel, whose init launches
// ChessApi.isAvailable() -> MaiaEngine.ensure() ->
// MaiaHandle.inAssets, which touches MlNative.isAvailable
// (System.loadLibrary("modelrunner") via NativeLoader); Y3MainActivityTest
// reaches the same probe by launching MainActivity, whose Game
// destination builds ChessViewModel. Tests that merely call the pure
// MaiaEngine.encodePlanes/moveIndex helpers (MaiaEncodingTest),
// compose boards/screens with fake actions, or drive the pure rules
// never load the library and stay in the main task. Quarantine the
// loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task
// so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.games.chess.ChessDropTest",
            "com.vayunmathur.games.chess.Y3ChessGameFlowTest",
            "com.vayunmathur.games.chess.Y3MainActivityTest",
            "com.vayunmathur.games.chess.util.ChessViewModelTest",
            "com.vayunmathur.games.chess.util.ViewModelCoverageTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // PuzzleViewModel/LearnViewModel collect their uiState in
    // viewModelScope and persist through the DataStoreUtils
    // process-wide singleton (its init eagerly collects the
    // preferences DataStore), OnlineChessViewModel launches its
    // Lichess event-stream/redirect collectors in init over the same
    // singleton via LichessTokenStore (whose sealerForTest is a
    // process-wide static and whose migration scope is never
    // cancelled), and LearnRepository/PuzzleRepository cache their
    // bundled data process-wide; persisted DataStore state also leaks
    // into classes expecting defaults (the online tests clear the
    // token store in setUp/tearDown for exactly this reason). Audit
    // rule: any test instantiating PuzzleViewModel / LearnViewModel /
    // OnlineChessViewModel, using LichessTokenStore directly, or
    // launching MainActivity (already quarantined as a native loader
    // above) belongs here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.games.chess.ChessPass3DrawTest",
            "com.vayunmathur.games.chess.LearnStageScreenTest",
            "com.vayunmathur.games.chess.OnlineScreensTest",
            "com.vayunmathur.games.chess.Y3LearnScreensTest",
            "com.vayunmathur.games.chess.Y3LichessNetTest",
            "com.vayunmathur.games.chess.Y3OnlineScreensTest",
            "com.vayunmathur.games.chess.Y3OnlineVmNetTest",
            "com.vayunmathur.games.chess.auth.AuthCoverageTest",
            "com.vayunmathur.games.chess.auth.LichessTokenStoreTest",
            "com.vayunmathur.games.chess.platform.OnlineChessViewModelTest",
            "com.vayunmathur.games.chess.platform.OnlineStreamCoverageTest",
            "com.vayunmathur.games.chess.platform.OnlineViewModelCoverageTest",
            "com.vayunmathur.games.chess.util.PuzzleAndLearnViewModelTest",
        )
    )
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:ml"))
    implementation(project(":library:network"))
    implementation(libs.androidx.browser)
}
