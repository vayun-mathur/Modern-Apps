plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "crossword"
    scale = 0.375
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.brotli.dec)
}
