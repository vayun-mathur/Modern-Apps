android {
    defaultConfig {
        applicationId = "com.vayunmathur.crypto"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

dependencies {
    // ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // navigation 3
    implementation(libs.androidx.navigation3.runtime)

    // solana
    implementation(libs.sol4k)
}