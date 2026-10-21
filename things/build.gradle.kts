plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "devices_other"
}

android {
    defaultConfig {
        versionCode = 20261021
        versionName = "v2.6.10"
        applicationId = "com.vayunmathur.things"
    }
}

dependencies {
    implementation(libs.androidx.connect.client)
}
