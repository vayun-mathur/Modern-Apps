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
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    // CSV parsing library for Bitwarden CSV imports
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}