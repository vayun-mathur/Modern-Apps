plugins {
    id("common-conventions-app")
    // androidx.tv, so the TV entry is built on a design system that knows what a D-pad is
    // (the cast:tv precedent).
    id("common-conventions-tv")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:youpipe:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "play_arrow"
}

// Google TV hardware runs a 32-bit userspace: an arm64-only APK is rejected at install.
nativeAbis {
    armv7 = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// YouTube (InnerTube) extractor — a Rust port of PipePipeExtractor. See youpipe/src/main/rust/.
// extraAbis: the .so must also load on 32-bit Google TV.
rustNativeLib("youpipe_extractor", "youpipe", extraAbis = listOf("armeabi-v7a"))

// Only the class below actually loads libyoupipe_extractor: it calls
// NativeExtractor.*, which initialises YouPipeNative (the one
// NativeLoader.load("youpipe_extractor") attempt) and then invokes the
// external functions. Tests that only exercise NativeExtractor.unwrap,
// the NewPipe-extractor helpers in network/Extractor.kt, or DTO/JSON
// contracts never initialise YouPipeNative and stay in the main task.
// Quarantine the loader and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.add("com.vayunmathur.youpipe.nativeext.NativeExtractorDispatchDeepTest")
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // YouPipeViewModel stateIns (SharingStarted.WhileSubscribed) more
    // than a dozen repository flows plus the process-wide DataStoreUtils
    // singleton's flows (sponsor-block, language, default page, playback
    // speed, ...) in viewModelScope, and is never cleared when the test
    // never calls onCleared; persisted DataStoreUtils state also leaks
    // into classes expecting defaults. Audit rule: any test
    // instantiating YouPipeViewModel (or launching MainActivity, which
    // builds one via its factory) belongs here. Tests that build only an
    // in-memory SubscriptionDatabase/repository seam without the
    // ViewModel batch. The two Sabr WebView classes below share the
    // process-wide SharedWebViewRuntime singleton (same Robolectric
    // @Config, hence the same sandbox classloader in a shared JVM):
    // each calls SharedWebViewRuntime.get(ctx).warmUp() and depends on
    // its stored init-failure latch, so they must not share a JVM with
    // each other either. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.youpipe.RootSlice3Pass2Test",
            "com.vayunmathur.youpipe.RootSlice3RouteTest",
            "com.vayunmathur.youpipe.network.sabr.SabrSlice1StoreDownloadTest",
            "com.vayunmathur.youpipe.network.sabr.SabrSlice1WebViewTest",
            "com.vayunmathur.youpipe.platform.ImportExportDeepTest",
            "com.vayunmathur.youpipe.platform.SharedLinkDeepTest",
            "com.vayunmathur.youpipe.platform.VideoLoadingDeepTest",
            "com.vayunmathur.youpipe.ui.C9Slice2PagesTest",
            "com.vayunmathur.youpipe.ui.ChannelPageDeepTest",
            "com.vayunmathur.youpipe.ui.DataSettingsRowsPass3Test",
            "com.vayunmathur.youpipe.ui.ExpandedWidthPass3Test",
            "com.vayunmathur.youpipe.ui.HistoryInteractionsPass3Test",
            "com.vayunmathur.youpipe.ui.ManageInterestsActionsPass3Test",
            "com.vayunmathur.youpipe.ui.OrientationHistoryPass3Test",
            "com.vayunmathur.youpipe.ui.PagesPass3Test",
            "com.vayunmathur.youpipe.ui.PhonePlayerSearchDeepTest",
            "com.vayunmathur.youpipe.ui.PhoneVideoDeepTest",
            "com.vayunmathur.youpipe.ui.PlayerMenusPass3Test",
            "com.vayunmathur.youpipe.ui.PlaylistInteractionsPass3Test",
            "com.vayunmathur.youpipe.ui.SavedPlaylistMorePass3Test",
            "com.vayunmathur.youpipe.ui.SettingsDialogsMorePass3Test",
            "com.vayunmathur.youpipe.ui.SettingsDownloadsPass3Test",
            "com.vayunmathur.youpipe.ui.SubscriptionsSettingsNavPass3Test",
            "com.vayunmathur.youpipe.ui.VideoPlayerPass3Test",
            "com.vayunmathur.youpipe.ui.ViewModelHarnessPass3Test",
            "com.vayunmathur.youpipe.ui.dialogs.RootSlice3PlaylistDialogsTest",
            "com.vayunmathur.youpipe.ui.tv.TvBrowseDeepTest",
            "com.vayunmathur.youpipe.ui.tv.TvFeedDeepTest",
            "com.vayunmathur.youpipe.ui.tv.TvPagesDeep2Test",
            "com.vayunmathur.youpipe.ui.tv.TvPagesTest",
            "com.vayunmathur.youpipe.ui.tv.TvPlayerDeepTest",
            "com.vayunmathur.youpipe.ui.tv.TvSearchChannelDeepTest",
            "com.vayunmathur.youpipe.ui.tv.TvSearchDownloadsDeepTest",
        )
    )
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.youpipe"
    }
}

dependencies {
    implementation(project(":library:tv"))
    implementation(project(":library:image"))
    implementation(project(":youpipe:extractor"))
    // Puts the video on a TV through the installed Cast app: a Surface and a PCM pipe, no sockets.
    implementation(project(":sdk:cast"))
    implementation(libs.quickjs.kt)
    implementation(libs.androidx.webkit)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    // DefaultHttpDataSource (HttpURLConnection) for progressive; SABR via library:network – no okhttp

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    // Android-only network – HttpURLConnection + own WS; no direct okhttp dep
    implementation(project(":library:network"))

    testImplementation(libs.androidx.work.testing)
}