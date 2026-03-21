plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260321
        versionName = "v2.2.1"
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    // CSV parsing library for Bitwarden CSV imports
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}