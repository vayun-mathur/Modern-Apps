plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:calculator:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "calculate"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calculator"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    // Live currency exchange rates fetched from api.maproj.org via the shared client.
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
    testImplementation(libs.androidx.work.testing)
}
