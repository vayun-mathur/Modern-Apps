plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260325
        versionName = "v2.2.2"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // solana
    implementation(libs.sol4k)
}