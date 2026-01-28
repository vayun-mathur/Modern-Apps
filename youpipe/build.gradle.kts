android {
    defaultConfig {
        applicationId = "com.vayunmathur.youpipe"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:local-SNAPSHOT")
    implementation(libs.okhttp)

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
}