plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260401
        versionName = "v2.3.0"
        applicationId = "com.vayunmathur.health"
    }
}

dependencies {
    // healthconnect
    implementation(libs.androidx.connect.client)

    implementation(libs.androidx.work.runtime.ktx)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}