plugins {
    alias(libs.plugins.ksp)
}

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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    val work_version = "2.9.0" // Use the latest version
    implementation("androidx.work:work-runtime-ktx:$work_version")
}