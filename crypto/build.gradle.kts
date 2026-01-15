android {
    defaultConfig {
        applicationId = "com.vayunmathur.crypto"
        versionCode = 20
        versionName = "2.0"
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
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3)

    // solana
    implementation(libs.sol4k)
}