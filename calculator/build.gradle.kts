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
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.calculator"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    // Live currency exchange rates fetched from api.maproj.org via the shared client.
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
}
