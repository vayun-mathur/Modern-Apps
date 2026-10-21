plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:calendar:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "calendar_month"
}

android {
    defaultConfig {
        versionCode = 20261021
        versionName = "v2.6.10"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))

}
