plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:launcher:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "apps"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.launcher"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.datastore.preferences)
    // Declared rather than relied on transitively: a launcher is mostly motion, so `Animatable`,
    // `animateBounds` and the animation specs are load-bearing here rather than incidental.
    implementation(libs.androidx.compose.animation)
}
