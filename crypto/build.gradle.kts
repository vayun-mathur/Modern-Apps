plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    // solana
    implementation(libs.sol4k)
}