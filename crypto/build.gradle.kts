plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260513
        versionName = "v2.4.5"
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementation(project(":library:network"))

    // solana
    implementation(libs.sol4k)
}