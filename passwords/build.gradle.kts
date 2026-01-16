android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
}