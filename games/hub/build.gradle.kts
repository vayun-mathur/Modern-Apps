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
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.games.hub"
    }
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:ui"))
    implementation(project(":library:room"))
    implementation(project(":sdk:games"))
    implementRoom(libs)
}
