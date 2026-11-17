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

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Visual-inertial tracking runs on the in-tree Rust CV core (`measure_cv`:
// Shi-Tomasi corners, pyramidal Lucas-Kanade, essential-matrix pose) behind
// the ArHitProvider seam. No third-party native CV library ships in the APK,
// so there is no prebuilt .so to fail 16 KB page-size alignment. See
// measure/src/main/rust/.
rustNativeLib("measure_cv", "measure")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
}
