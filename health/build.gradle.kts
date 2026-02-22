plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.health"
    }
}

dependencies {
    implementation(libs.androidx.connect.client)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
}