plugins {
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.youpipe"
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:local-SNAPSHOT")
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}