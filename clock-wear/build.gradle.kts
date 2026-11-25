plugins {
    id("common-conventions-wear")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Renders land in this module's own
    // metadata_data/photos/<wear-module-key>/wear/ bucket.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "schedule"
}

android {
    // WearLink's handshake token requires the same signing certificate on
    // both sides, so this module MUST ship as the phone app's package: same
    // applicationId (below) and the same signing config the convention
    // plugins already apply to both (debug key for dev, release key for
    // release). The namespace only names this module's own R/resources.
    namespace = "com.vayunmathur.clock.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.clock"
        // Convention defaults to 30, but :library:log (pulled in by the wear
        // convention) requires 31, so the watch floor is 31.
        minSdk = 31
    }
}

dependencies {
    // Watch side of the Wear OS sync (see domain/WearClockProtocol.kt, the
    // copy of the phone protocol it must stay in step with).
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
