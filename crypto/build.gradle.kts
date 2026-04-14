plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260413
        versionName = "v2.3.2"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    // solana
    implementation(libs.sol4k)
}