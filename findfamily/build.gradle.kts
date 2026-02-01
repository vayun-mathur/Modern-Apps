android {
    defaultConfig {
        applicationId = "com.vayunmathur.findfamily"
        versionCode = 1
        versionName = "1.0"
    }
}

plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)
}