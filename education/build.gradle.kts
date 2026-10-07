plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:education:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "school"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.education"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))

    // Video playback (Khan/YouTube streaming via NewPipe + media3), like :youpipe.
    implementation(project(":youpipe:extractor"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(project(":library:network"))

}
