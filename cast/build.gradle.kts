plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "cast"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.cast"
    }
    // MediaProxyServer is sockets and lifecycle, and every branch of it logs. Its teardown is worth
    // proving on the JVM, so android.util.Log has to answer rather than throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// This module has no Rust of its own, but :cast:protocol (and the
// test-only dep below) reach :library:e2ee-p2p, which registers
// libe2ee_pqc (rustNativeLib), so every unit test here has kept
// forkEvery = 1. Only the classes below actually load that library:
// they construct FakeTv, whose init runs PqcIdentity.loadOrCreate over
// an empty in-memory store -> Pqc.generateKem/generateDsa ->
// PqcNative (NativeLoader.load("e2ee_pqc")), and/or pair a
// MirrorClient / CastController against it, which seals the session
// secret via SecretSealing.seal -> Pqc.encryptTo and opens it in
// FakeTv via SecretSealing.open -> PqcIdentity.decrypt. Tests that
// merely drive the pure protocol/codec helpers, compose with fake
// actions, or run MirrorClient against a closed port never load the
// library and stay in the main task. Quarantine the loaders and the
// shared-JVM polluters below into testDevUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDevUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.cast.platform.CastControllerTest",
            "com.vayunmathur.cast.platform.ConnectionExtraTest",
            "com.vayunmathur.cast.platform.ControllerBranchesTest",
            "com.vayunmathur.cast.platform.DesktopAndCaptureTest",
            "com.vayunmathur.cast.platform.DesktopRenegotiateTest",
            "com.vayunmathur.cast.platform.MirrorClientExtraTest",
            "com.vayunmathur.cast.platform.MirrorClientTest",
            "com.vayunmathur.cast.platform.ProviderRouteTest",
            "com.vayunmathur.cast.platform.SessionEncoderBackedTest",
            "com.vayunmathur.cast.platform.TileIdleClickTest",
            "com.vayunmathur.cast.platform.mirror.EncoderBackedTest",
            "com.vayunmathur.cast.service.ContentCastIntegrationTest",
            "com.vayunmathur.cast.service.ContentStartedTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // CastViewModel turns CastController's flows into a viewModelScope
    // stateIn collector (SharingStarted.WhileSubscribed) that is never
    // cleared when the test never calls onCleared, and
    // MirrorPreferences reads/writes through the DataStoreUtils
    // process-wide singleton (its init eagerly collects the preferences
    // DataStore), so persisted DataStore state leaks into classes
    // expecting defaults. Audit rule: any test instantiating
    // CastViewModel or launching MainActivity (which builds one), or
    // using MirrorPreferences directly, belongs here, unless it already
    // loads the native library above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.cast.platform.ActivitiesTest",
            "com.vayunmathur.cast.platform.CastViewModelTest",
            "com.vayunmathur.cast.platform.RemoteDisplayAndTileTest",
            "com.vayunmathur.cast.platform.mirror.MirrorPreferencesEmptyTest",
            "com.vayunmathur.cast.platform.mirror.MirrorPreferencesTest",
            "com.vayunmathur.cast.ui.CastComposeTest",
        )
    )
}

dependencies {
    // Compile-only stubs for `com.android.media.remotedisplay`, the remote display provider API.
    // A shared library on the system image, pulled in at runtime by the `<uses-library>` tag;
    // must NOT be packaged. See :library:aosp-stubs.
    compileOnly(project(":library:aosp-stubs"))
    // The wire format, shared verbatim with the TV receiver. Nothing here re-implements it.
    implementation(project(":cast:protocol"))
    // The IPC contract, shared with every app that streams its content through here. Depended on
    // rather than duplicated, which is the one thing FamilyLocationProtocol gets wrong.
    implementation(project(":sdk:cast"))
    // Test-only: the fake TV in unit tests holds a real PQC identity to seal against.
    testImplementation(project(":library:e2ee-p2p"))
    // Test-only: lets Robolectric load the remote-display provider against the stub surface.
    testImplementation(project(":library:aosp-stubs"))
}

// No Rust crate. The protocol module already reaches ML-KEM through :library:e2ee-p2p, and
// everything left here is Android platform work: MediaProjection, MediaCodec, sockets.
