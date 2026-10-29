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
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.games.hub"
    }
}

dependencies {
    implementation(project(":library"))
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
