plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:arrows:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "double_arrow"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.games.arrows"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
}
