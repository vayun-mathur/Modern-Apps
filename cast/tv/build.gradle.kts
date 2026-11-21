plugins {
    id("common-conventions-app")
    // androidx.tv, so this app is built on a design system that knows what a D-pad is. Named here
    // *alongside* the app convention rather than instead of it, even though it applies that itself:
    // the type-safe `launcherIcon` and `nativeAbis` accessors below are generated from the plugins this
    // file applies directly.
    id("common-conventions-tv")
    // No preview-metadata convention. Every screen here is either a full-screen decoder surface or
    // a TV-shaped idle screen; hand-place any listing images under
    // metadata_data/photos/cast-tv/tv/ (the release build maps each bucket to its
    // Triple-T graphics/<bucket>-screenshots dir). `camera` and `games/voxels`
    // set the precedent for opting out. metadata_data/cast-tv.md still exists, which
    // is what makes release.sh generate a listing at all.
}

launcherIcon {
    symbol = "cast"
}

// Google TV hardware (the onn 4K Streaming Box and friends) runs a 32-bit userspace and
// reports only `armeabi-v7a,armeabi`, so an arm64-only APK is rejected at install time.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.cast.tv"
    }
}

// This module has no Rust of its own, but :library:e2ee-p2p (below)
// registers libe2ee_pqc (rustNativeLib), so every unit test here has
// kept forkEvery = 1. Only the classes below actually load that
// library: they call ReceiverController.start (whose listen() runs
// PqcIdentity.loadOrCreate over PairingStore -> Pqc.generateKem /
// generateDsa -> PqcNative, NativeLoader.load("e2ee_pqc")), seal a
// session secret directly via SecretSealing.seal -> Pqc.encryptTo, or
// drive ReceiverService.onStartCommand / MainActivity, which start
// the controller the same way. Tests that merely call the pure
// pairing/session helpers, compose TV surfaces, or drive decoders
// with fake codecs never load the library and stay in the main task.
// Quarantine the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task
// so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.cast.tv.ReceiverShellTest",
            "com.vayunmathur.cast.tv.ServiceNotificationsTest",
            "com.vayunmathur.cast.tv.platform.ContentSessionSuccessTest",
            "com.vayunmathur.cast.tv.platform.ControlChannelDeepTest",
            "com.vayunmathur.cast.tv.platform.FullSessionTest",
            "com.vayunmathur.cast.tv.service.ServiceCollectTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // ReceiverController is a process-wide object (state StateFlow,
    // serverSocket, session/media jobs, surface/contentPlayer fields)
    // whose session state one class leaves behind is the next class's
    // starting phase, and PairingStore persists through the
    // DataStoreUtils process-wide singleton (its init eagerly collects
    // the preferences DataStore), so remembered-device keys and the
    // friendly name leak into classes expecting defaults. Audit rule:
    // any test calling ReceiverController.authenticate / serveContent /
    // startStreaming / pump, mutating its state or fields, or using
    // PairingStore / DataStoreUtils directly belongs here, unless it
    // already loads the native library above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.cast.tv.platform.AuthenticateDeepTest",
            "com.vayunmathur.cast.tv.platform.AuthenticateTest",
            "com.vayunmathur.cast.tv.platform.FinalSmallTest",
            "com.vayunmathur.cast.tv.platform.PlatformTvTest",
            "com.vayunmathur.cast.tv.platform.ServeContentDeepTest",
            "com.vayunmathur.cast.tv.platform.StartStreamingDeepTest",
            "com.vayunmathur.cast.tv.platform.StreamingPumpDeepTest",
        )
    )
}

dependencies {
    // The wire format, shared verbatim with the phone. Nothing in this app re-implements it.
    implementation(project(":cast:protocol"))
    implementation(project(":library:e2ee-p2p"))
    // The shared TV design system: theme, cards, controls, states, transport. Deliberately not
    // :library:image, so this offline receiver gains no INTERNET permission from its UI.
    implementation(project(":library:tv"))
    // App content arrives as media rather than as pixels now, so the TV needs a player. Not
    // `media3-session`: the TV already has a remote-control path through its own control channel,
    // and a MediaSession would be a second one competing with it.
    implementation(libs.androidx.media3.exoplayer)
}
