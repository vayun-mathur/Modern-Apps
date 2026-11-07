plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    // WorkManager directly: this module's workers use only androidx.work APIs
    // (the :library:work helpers schedule that module's own reminders, nothing
    // reusable here), so no library-to-library edge is needed (#884).
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockito.core)
}
