plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "school"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.education"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.coil.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // Video playback (Khan/YouTube streaming via NewPipe + media3), like :youpipe.
    implementation(project(":youpipe:extractor"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(project(":library:network"))

    testImplementation(libs.junit)
}
