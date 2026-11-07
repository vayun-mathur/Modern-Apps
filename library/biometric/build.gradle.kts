plugins {
    id("common-conventions-library")
}

dependencies {
    // BiometricDatabaseHelper extends the DatabaseHelper keystore base from
    // :library, so this edge stays: both modules are permission-free (#884).
    implementation(project(":library"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    // The instrumented unlock tests drive the system credential screen.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
