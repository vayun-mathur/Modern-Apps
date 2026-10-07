plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "cast"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.cast"
    }
    // MediaProxyServer is sockets and lifecycle, and every branch of it logs. Its teardown is worth
    // proving on the JVM, so android.util.Log has to answer rather than throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compile-only stubs for `com.android.media.remotedisplay`, the remote display provider API.
    // A shared library on the system image, pulled in at runtime by the `<uses-library>` tag;
    // must NOT be packaged. See :library:remotedisplay-stubs.
    compileOnly(project(":library:remotedisplay-stubs"))
    // The wire format, shared verbatim with the TV receiver. Nothing here re-implements it.
    implementation(project(":cast:protocol"))
    // The IPC contract, shared with every app that streams its content through here. Depended on
    // rather than duplicated, which is the one thing FamilyLocationProtocol gets wrong.
    implementation(project(":sdk:cast"))
}

// No Rust crate. The protocol module already reaches ML-KEM through :library:e2ee-p2p, and
// everything left here is Android platform work: MediaProjection, MediaCodec, sockets.
