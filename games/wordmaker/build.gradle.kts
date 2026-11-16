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
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    testImplementation(libs.mockito.core)
    implementation(project(":sdk:games"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.brotli.dec)
}
