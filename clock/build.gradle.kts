plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260401
        versionName = "v2.3.0"
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
