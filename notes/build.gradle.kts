plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}