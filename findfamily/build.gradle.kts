plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {
    // ktor
    implementKtor(libs)

    implementRoom(libs)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)

    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
}