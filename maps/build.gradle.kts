plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.maps"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.composeMaterial3)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation("org.wololo:flatgeobuf:3.28.2")

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.navigation3.runtime)
    ksp(libs.androidx.room.compiler)
}