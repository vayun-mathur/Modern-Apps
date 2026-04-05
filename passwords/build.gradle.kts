plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
}

dependencies {
    implementRoom(libs)

    // CSV parsing library for Bitwarden CSV imports
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}