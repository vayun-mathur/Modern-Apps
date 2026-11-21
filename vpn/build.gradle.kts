plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:vpn:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "vpn_lock"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.vpn"
        minSdk = 31 // required for VpnService with per-app config etc (keep same as others)
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

rustNativeLib("vpn_wireguard", "vpn")

// Only the classes below actually load libvpn_wireguard: they call
// VpnNative key/tunnel externs directly (guarded by a nativeAvailable()
// probe, but the call still binds the library in that JVM). Tests that
// merely parse configs, inspect packets, or exercise the pure service
// helpers never touch VpnNative and stay in the main task. Quarantine
// the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.vpn.service.FinalCoverageBatchTest",
            "com.vayunmathur.vpn.service.PumpLoopTest",
            "com.vayunmathur.vpn.service.TunnelSessionMoreTest",
            "com.vayunmathur.vpn.service.TwoTunnelRouteTest",
            "com.vayunmathur.vpn.service.VpnTunnelServiceTest",
            "com.vayunmathur.vpn.util.VpnNativeTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // VpnViewModel's init starts a never-ending viewModelScope poll loop
    // plus WhileSubscribed stateIn collectors that are never cleared when
    // the test never calls onCleared, and BypassList reads/writes the
    // process-wide DataStoreUtils singleton (persisted bypass set leaks
    // into classes expecting defaults). Audit rule: any test
    // instantiating VpnViewModel (or rendering a page that takes one) or
    // using BypassList directly belongs here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.vpn.platform.VpnViewModelTest",
            "com.vayunmathur.vpn.ui.FinalVpnBatchTest",
            "com.vayunmathur.vpn.ui.MainActivityAndBypassSwitchTest",
            "com.vayunmathur.vpn.ui.NavigationAndPagesTest",
            "com.vayunmathur.vpn.ui.PageCallbacksTest",
            "com.vayunmathur.vpn.ui.VpnComposeScreensTest",
        )
    )
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.datastore.preferences)
    // User-supplied VPN endpoint: cannot pin, must use platform SYSTEM trust; document escape hatch.
    implementation(project(":library:network"))
}
