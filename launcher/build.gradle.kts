plugins {
    id("common-conventions-app")
    // androidx.tv, so the in-app TV mode is built on a design system that knows what a
    // D-pad is. Named alongside (the cast:tv precedent): the launcherIcon/nativeAbis
    // accessors are generated from directly-applied plugins.
    id("common-conventions-tv")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:launcher:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "apps"
}

// Google TV hardware runs a 32-bit userspace: an arm64-only APK is rejected at install.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
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
