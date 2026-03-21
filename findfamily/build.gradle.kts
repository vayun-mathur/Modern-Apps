plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260321
        versionName = "v2.2.1"
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {
    // ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

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