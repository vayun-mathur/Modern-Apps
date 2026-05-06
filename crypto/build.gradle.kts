plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260506
        versionName = "v2.4.4"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    // solana
    implementation(libs.sol4k)
}