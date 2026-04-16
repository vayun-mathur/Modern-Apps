plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260416
        versionName = "v2.3.3"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    // solana
    implementation(libs.sol4k)
}