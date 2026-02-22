android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.findfamily"
    }
}

plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    // ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)

    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
}