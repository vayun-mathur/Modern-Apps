plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "share"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.share"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Rust protocol crate (UKEY2, protobuf framing, secure messages, payload).
// See share/src/main/rust/ and share/PROTOCOL_CONTRACT.md.
rustNativeLib("share_nearby", "share")

// Only the classes below actually load libshare_nearby: they call
// ShareNativeDiscovery.native* directly, construct ShareSession
// (ShareNative.nativeInit), run TcpTransport listen/connect (which
// creates a ShareSession per socket), drive the Ble/Nsd discovery
// managers (native advertisement/endpoint codecs, NSD service type),
// or touch ShareReceiveController (ensure() builds endpoint info via
// native, transport() is the process-wide TcpTransport). Tests that
// merely read the inlined ShareNative.DEVICE_TYPE_PHONE constant, use
// the pure plist/HTTP/DvZip helpers, compose with FakeActions, or drive
// a per-instance AirDropReceiver/ShareReceiveNotifier never load the
// library and stay in the main task. Quarantine the loaders and the
// shared-JVM polluters below into testDevUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDevUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.share.domain.protocol.ShareSessionTest",
            "com.vayunmathur.share.network.transport.TcpTransportExtraTest",
            "com.vayunmathur.share.network.transport.TcpTransportTest",
            "com.vayunmathur.share.platform.FinalPushTest",
            "com.vayunmathur.share.platform.GapPush2Test",
            "com.vayunmathur.share.platform.GapPush3Test",
            "com.vayunmathur.share.platform.GapPush4Test",
            "com.vayunmathur.share.platform.GapPush5Test",
            "com.vayunmathur.share.platform.GapPush6Test",
            "com.vayunmathur.share.platform.GapPush7Test",
            "com.vayunmathur.share.platform.GapPush8Test",
            "com.vayunmathur.share.platform.GapPush9Test",
            "com.vayunmathur.share.platform.GapPushTest",
            "com.vayunmathur.share.platform.MoreCoverageTest",
            "com.vayunmathur.share.platform.ShareViewModelConnectTest",
            "com.vayunmathur.share.platform.discovery.DiscoveryManagersTest",
            "com.vayunmathur.share.platform.receive.ReceiveComponentsTest",
            "com.vayunmathur.share.protocol.ShareNativeDiscoveryTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // ShareViewModel turns its combined flows into a viewModelScope
    // stateIn collector (SharingStarted.WhileSubscribed) that is never
    // cleared when the test never calls onCleared, and
    // ShareReceiveController is a process-wide singleton (cached
    // transport/managers/notifier, IO scope, mutable localName) over
    // the DataStoreUtils singleton, whose init eagerly collects the
    // preferences DataStore; persisted DataStore state also leaks into
    // classes expecting defaults. Audit rule: any test instantiating
    // ShareViewModel or launching MainActivity / SharePopupActivity
    // (which build one) belongs here, unless it already loads the
    // native library above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.share.platform.ShareViewModelHelpersTest",
            "com.vayunmathur.share.ui.SharePopupAndActivitiesTest",
        )
    )
}

dependencies {
    // ViewModel/Compose already via common-conventions-app; explicit lifecycle for clarity.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // @SystemApi NetworkAgent/NetworkProvider for the Mosey `mosey0` AirDrop link; compile-only,
    // must NOT be packaged. See :library:aosp-stubs.
    compileOnly(project(":library:aosp-stubs"))
}
