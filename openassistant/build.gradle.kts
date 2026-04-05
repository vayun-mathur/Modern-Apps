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
    // room
    implementRoom(libs)

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(libs.coil.compose)

    // ai
    implementation(libs.litertlm.android)

    // markdown
    implementation(libs.compose.markdown)
}