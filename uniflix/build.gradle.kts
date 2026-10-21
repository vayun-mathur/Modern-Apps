plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "movie"
}

android {
    defaultConfig {
        versionCode = 20261021
        versionName = "v2.6.10"
        applicationId = "com.vayunmathur.uniflix"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:image"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.datastore.preferences)
}
