plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.photos"
    }
}

dependencies {
    implementation(libs.maplibre.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)

    implementRoom(libs)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)


    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}