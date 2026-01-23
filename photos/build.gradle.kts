plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.photos"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.composeMaterial3)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}