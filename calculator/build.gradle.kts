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
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.calculator"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    // Live currency exchange rates fetched from api.vayunmathur.com via the shared client.
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
}
