plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.health"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

dependencies {
    implementation(libs.androidx.connect.client)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
}