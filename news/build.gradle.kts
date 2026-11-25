plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:news:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "newspaper"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.news"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:format"))
    implementation(project(":library:image"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}
