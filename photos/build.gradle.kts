plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.photos"
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.composeMaterial3)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)
    ksp(libs.androidx.room.compiler)


    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}