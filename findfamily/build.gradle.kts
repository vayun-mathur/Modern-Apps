plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    implementRoom(libs)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)

    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
}