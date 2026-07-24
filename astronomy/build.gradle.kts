plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "star"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.astronomy"
    }
}

metadataScreenshots {
    // SkyMapPage gates behind location via PermissionsChecker — grant so first-run
    // system prompt doesn't hijack the screenshots. Camera is optional AR overlay.
    permissions.addAll(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION"
    )
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    // Unit tests for Phase 7 (JVM, no Android)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
}
