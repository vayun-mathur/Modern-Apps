plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
}

dependencies {
    implementRoom(libs)

    // CSV parsing library for Bitwarden CSV imports
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}