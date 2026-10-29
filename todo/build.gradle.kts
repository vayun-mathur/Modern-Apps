plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:todo:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "checklist"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.todo"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
}
