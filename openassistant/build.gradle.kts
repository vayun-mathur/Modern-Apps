plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.openassistant"
    }
}

dependencies {
    // ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    // markdown
    implementation(libs.compose.markdown)
}