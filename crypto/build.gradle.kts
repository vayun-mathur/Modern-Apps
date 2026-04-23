plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260422
        versionName = "v2.4.1"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    // solana
    implementation(libs.sol4k)
}