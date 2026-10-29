plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:everysync:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "sync"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.everysync"
    }
}

dependencies {
    // Background sync
    implementation(libs.androidx.work.runtime.ktx)

    // HTTP client (relay REST + WebSocket)
    implementation(project(":library:network"))

    // End-to-end encryption for personal relay vaults (PQC identity + AES content keys)
    implementation(project(":library:e2ee-p2p"))

}
