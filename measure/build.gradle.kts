plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:measure:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "straighten"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.measure"
    }
}

// Visual-inertial tracking runs on OpenCV (org.opencv:opencv, Maven Central — see
// the catalog entry for provenance). The home-grown Rust `measure_vio` engine is no
// longer wired into the measurement flow: its crate stays in the tree at
// measure/src/main/rust/ for reference but is referenced by nothing.
dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.opencv)
}
