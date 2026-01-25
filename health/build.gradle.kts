plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.health"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.2.0-alpha02")

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
}