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
    implementation(project(":library:network"))

    implementRoom(libs)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)

    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)

    // Ultra-Wideband ranging (Precision Finding screen)
    implementation("androidx.core.uwb:uwb:1.0.0-alpha11")
}