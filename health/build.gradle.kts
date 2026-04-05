plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
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