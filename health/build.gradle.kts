plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        applicationId = "com.vayunmathur.health"
    }
}

dependencies {
    // healthconnect
    implementation(libs.androidx.connect.client)

    implementation(libs.androidx.work.runtime.ktx)

    // room
    implementRoom(libs)
}