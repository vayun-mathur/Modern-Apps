plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
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
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.litertlm.android)


    // markdown
    implementation(libs.compose.markdown)
}